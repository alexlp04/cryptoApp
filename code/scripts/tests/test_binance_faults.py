"""test_binance_faults.py — Qué hacen el REST y el stream de velas cuando Binance falla.

Se levanta un Binance de mentira (tests/fake_binance.py) y se le pasan los motores de verdad: nada de
mocks de requests ni de websockets. Cada test es un fallo que en producción acabará pasando.
"""
from __future__ import annotations

import asyncio
import json
from collections import deque

import engine_rt
import pytest
import requests
import rt_stream
import shared_utils
from fake_binance import BASE, Close, Drop, FakeRest, FakeWs, Hang, Reply, Send, klines, tick

MINUTE = 60_000


@pytest.fixture(autouse=True)
def _tiempos_cortos(monkeypatch):
    monkeypatch.setattr(shared_utils, "KLINES_MAX_WAIT_SECONDS", 0.01)
    monkeypatch.setattr(shared_utils, "KLINES_TIMEOUT_SECONDS", 0.4)
    monkeypatch.setattr(rt_stream, "STALE_AFTER_SECONDS", 0.4)
    monkeypatch.setattr(rt_stream, "INITIAL_RETRY_DELAY", 0.02)
    monkeypatch.setattr(rt_stream, "MAX_RETRY_DELAY", 0.1)


def _rest(monkeypatch, *script: Reply, fallback: Reply | None = None) -> FakeRest:
    rest = FakeRest(list(script), fallback or Reply(body=klines(0, 3)))
    monkeypatch.setattr(shared_utils, "KLINES_URL", rest.url)
    return rest


# ───────────────────────────── REST: /klines ─────────────────────────────

def test_un_429_con_retry_after_se_reintenta_y_acaba_dando_las_velas(monkeypatch):
    with _rest(monkeypatch, Reply(429, {"code": -1003, "msg": "Too many requests"}, {"Retry-After": "0"})) as rest:
        velas = shared_utils.fetch_recent_klines("BTCUSDT", "1m", 3)

    assert [v["timestamp"] for v in velas] == [BASE + i * MINUTE for i in range(3)]
    assert len(rest.requests) == 2


@pytest.mark.parametrize("status", [500, 502, 503, 504])
def test_los_errores_de_servidor_se_reintentan(monkeypatch, status):
    with _rest(monkeypatch, Reply(status, "<html>maintenance</html>"), Reply(status, "")) as rest:
        velas = shared_utils.fetch_recent_klines("BTCUSDT", "1m", 3)

    assert len(velas) == 3
    assert len(rest.requests) == 3


def test_un_baneo_418_que_no_se_levanta_acaba_en_error_tras_los_intentos(monkeypatch):
    ban = Reply(418, {"code": -1003, "msg": "banned"}, {"Retry-After": "0"})
    with _rest(monkeypatch, fallback=ban) as rest, pytest.raises(requests.exceptions.HTTPError):
        shared_utils.fetch_recent_klines("BTCUSDT", "1m", 3)

    assert len(rest.requests) == shared_utils.KLINES_ATTEMPTS


def test_un_simbolo_invalido_no_se_reintenta(monkeypatch):
    invalido = Reply(400, {"code": -1121, "msg": "Invalid symbol."})
    with _rest(monkeypatch, fallback=invalido) as rest, pytest.raises(requests.exceptions.HTTPError):
        shared_utils.fetch_recent_klines("NOEXISTE", "1m", 3)

    assert len(rest.requests) == 1


@pytest.mark.parametrize("cuerpo", ["<html>Access denied</html>", '[[1, "1", "2"', "", '{"code":-1,"msg":"x"}'])
def test_un_cuerpo_que_no_es_la_lista_de_velas_se_reintenta(monkeypatch, cuerpo):
    with _rest(monkeypatch, Reply(200, cuerpo)) as rest:
        velas = shared_utils.fetch_recent_klines("BTCUSDT", "1m", 3)

    assert len(velas) == 3
    assert len(rest.requests) == 2


def test_un_servidor_que_tarda_mas_que_el_timeout_se_reintenta(monkeypatch):
    with _rest(monkeypatch, Reply(200, klines(0, 3), delay=1.0)) as rest:
        velas = shared_utils.fetch_recent_klines("BTCUSDT", "1m", 3)

    assert len(velas) == 3
    assert len(rest.requests) == 2


def test_sin_servidor_el_error_llega_al_llamador_tras_los_intentos(monkeypatch):
    monkeypatch.setattr(shared_utils, "KLINES_URL", "http://127.0.0.1:1/api/v3/klines")  # puerto sin escuchar
    with pytest.raises(requests.exceptions.ConnectionError):
        shared_utils.fetch_recent_klines("BTCUSDT", "1m", 3)


def test_un_calentamiento_de_mas_de_1000_velas_se_pide_por_paginas(monkeypatch):
    paginas = [Reply(body=klines(500, 1000)), Reply(body=klines(0, 500))]
    with _rest(monkeypatch, *paginas) as rest:
        velas = shared_utils.fetch_recent_klines("BTCUSDT", "1m", 1500)

    assert [v["timestamp"] for v in velas] == [BASE + i * MINUTE for i in range(1500)]
    assert [r["limit"] for r in rest.requests] == ["1000", "500"]
    assert rest.requests[1]["endTime"] == str(BASE + 500 * MINUTE - 1)


def test_si_el_rest_no_responde_el_sembrado_lo_dice_y_no_toca_el_buffer(monkeypatch):
    buffer = deque([{"timestamp": BASE, "open": 1, "high": 1, "low": 1, "close": 1, "volume": 1}])
    with _rest(monkeypatch, fallback=Reply(503, "")):
        ok, missed = asyncio.run(shared_utils.seed_buffer(buffer, "BTCUSDT", "1m", 10))

    assert (ok, missed) == (False, [])
    assert len(buffer) == 1


# ─────────────────────────── WebSocket: el stream ───────────────────────────

async def _collect(n: int, ws_server: FakeWs, buffer: deque | None = None, timeout: float = 15.0):
    """Recoge n eventos del stream real, metiendo cada vela en el buffer como hacen los motores."""
    buf = buffer if buffer is not None else deque(maxlen=500)
    events: list = []

    async def run() -> None:
        async for event in rt_stream.kline_events("BTCUSDT", "1m", buf, 100, shared_utils.fetch_recent_klines):
            shared_utils.upsert_candle(buf, {"timestamp": int(event.k["t"]), "open": 1.0, "high": 1.0,
                                             "low": 1.0, "close": 1.0, "volume": 1.0})
            events.append(event)
            if len(events) >= n:
                return

    await asyncio.wait_for(run(), timeout)
    return events, buf


def _stream(monkeypatch, sessions, n, rest_script=(), rest_fallback=None, buffer=None):
    """Arranca REST y WebSocket falsos y devuelve (eventos, buffer, servidor ws)."""
    rest = _rest(monkeypatch, *rest_script, fallback=rest_fallback)

    async def main():
        async with FakeWs(sessions) as ws:
            monkeypatch.setattr(shared_utils, "WS_BASE_URL", ws.base_url)
            events, buf = await _collect(n, ws, buffer)
            return events, buf, ws

    with rest:
        return asyncio.run(main())


def test_tras_un_corte_brusco_reconecta_y_sigue_dando_velas(monkeypatch):
    sesiones = [[Send(tick(0)), Send(tick(1)), Drop()], [Send(tick(2)), Send(tick(3))]]

    events, _, ws = _stream(monkeypatch, sesiones, 4)

    assert [int(e.k["t"]) for e in events] == [BASE + i * MINUTE for i in range(4)]
    assert ws.connections == 2


def test_el_cierre_ordenado_del_servidor_cada_24h_reconecta_enseguida(monkeypatch):
    sesiones = [[Send(tick(0)), Close()], [Send(tick(1))]]

    events, _, ws = _stream(monkeypatch, sesiones, 2)

    assert len(events) == 2 and ws.connections == 2


def test_un_codigo_de_cierre_de_error_tambien_reconecta(monkeypatch):
    sesiones = [[Send(tick(0)), Close(code=1011)], [Send(tick(1))]]

    events, _, ws = _stream(monkeypatch, sesiones, 2)

    assert len(events) == 2 and ws.connections == 2


def test_un_stream_conectado_pero_en_silencio_se_da_por_muerto_y_reconecta(monkeypatch):
    sesiones = [[Hang()], [Send(tick(0))]]

    events, _, ws = _stream(monkeypatch, sesiones, 1)

    assert len(events) == 1 and ws.connections == 2


def test_los_mensajes_basura_se_ignoran_sin_reconectar(monkeypatch):
    basura = [Send("esto no es json"), Send("[1, 2, 3]"), Send(json.dumps({"result": None, "id": 1})),
              Send(json.dumps({"k": {"t": 1}})), Send(json.dumps({"k": "no soy un dict"})),
              Send(json.dumps({"error": {"code": 2, "msg": "Invalid request"}}))]

    events, _, ws = _stream(monkeypatch, [[*basura, Send(tick(0))]], 1)

    assert len(events) == 1 and ws.connections == 1


def test_una_vela_retrasada_de_otra_anterior_se_descarta(monkeypatch):
    sesiones = [[Send(tick(5)), Send(tick(4)), Send(tick(5, price=101))]]

    events, buf, _ = _stream(monkeypatch, sesiones, 2, rest_fallback=Reply(body=[]))

    assert [int(e.k["t"]) for e in events] == [BASE + 5 * MINUTE] * 2
    assert [c["timestamp"] for c in buf] == [BASE + 5 * MINUTE]


def test_sin_servidor_reintenta_con_espera_creciente_y_sin_caerse(monkeypatch):
    esperas: list[float] = []
    real_sleep = asyncio.sleep

    async def sleep(delay):
        esperas.append(delay)
        if len(esperas) >= 4:
            raise asyncio.CancelledError
        await real_sleep(0)

    monkeypatch.setattr(shared_utils, "WS_BASE_URL", "ws://127.0.0.1:1/ws")  # puerto cerrado
    monkeypatch.setattr(rt_stream.asyncio, "sleep", sleep)
    with _rest(monkeypatch), pytest.raises(asyncio.CancelledError):
        asyncio.run(_collect(1, None))

    assert esperas == [0.02, 0.04, 0.08, 0.1]  # dobla hasta el tope


def test_al_reconectar_se_resiembra_y_se_entregan_las_velas_que_se_perdieron(monkeypatch):
    # velas 0-2 al conectar; el stream cae viendo la 3; al volver el REST ya trae hasta la 5
    sesiones = [[Send(tick(3, closed=False)), Send(tick(3, closed=False)), Drop()], [Send(tick(6))]]
    rest = [Reply(body=klines(0, 3)), Reply(body=klines(0, 6))]

    events, buf, _ = _stream(monkeypatch, sesiones, 3, rest_script=rest)

    assert events[0].missed == []
    assert [c["timestamp"] for c in events[2].missed] == [BASE + i * MINUTE for i in (3, 4, 5)]
    assert shared_utils.buffer_is_contiguous(buf, "1m")
    assert [c["timestamp"] for c in buf][-1] == BASE + 6 * MINUTE


def test_si_el_rest_falla_al_resembrar_se_reintenta_en_la_siguiente_vela(monkeypatch):
    sesiones = [[Send(tick(3, closed=False)), Drop()], [Send(tick(3, closed=False)), Send(tick(4, closed=False))]]
    rest = [Reply(body=klines(0, 3))] + [Reply(503, "")] * shared_utils.KLINES_ATTEMPTS + [Reply(body=klines(0, 4))]

    events, buf, _ = _stream(monkeypatch, sesiones, 3, rest_script=rest)

    assert events[1].missed == []  # mismo tick de la vela 3: aún sin poder resembrar
    assert [c["timestamp"] for c in events[2].missed] == [BASE + 3 * MINUTE]  # la vela 4 lo reintenta y lo logra
    assert shared_utils.buffer_is_contiguous(buf, "1m")


# ─────────────────────────── El motor, de punta a punta ───────────────────────────

class _Strategy:
    WARMUP_PERIOD = 1

    def __init__(self) -> None:
        self.calls = 0

    def populate_indicators(self, df):
        self.calls += 1
        return df

    def should_buy(self, row):
        return False

    def should_sell(self, row):
        return False


def _run_engine(monkeypatch, capsys, sessions, rest_script, strategy, open_position=None, seconds=3.0,
                rest_fallback=None):
    """Corre engine_rt.run_symbol contra el Binance falso `seconds` segundos y devuelve las señales emitidas."""
    monkeypatch.setattr(engine_rt, "load_strategy_by_path", lambda *a, **k: strategy)

    async def main():
        async with FakeWs(sessions) as ws:
            monkeypatch.setattr(shared_utils, "WS_BASE_URL", ws.base_url)
            with pytest.raises(asyncio.TimeoutError):
                await asyncio.wait_for(
                    engine_rt.run_symbol("BTCUSDT", "1m", "x.py", 1000.0, 0.02, False,
                                         open_position=open_position), seconds)

    with _rest(monkeypatch, *rest_script, fallback=rest_fallback):
        asyncio.run(main())
    lines = [ln for ln in capsys.readouterr().out.splitlines() if ln.startswith("SIGNAL")]
    return [json.loads(ln.split("\t", 1)[1]) for ln in lines]


def test_un_stop_tocado_mientras_el_stream_estaba_caido_cierra_la_posicion_al_volver(monkeypatch, capsys):
    # posición larga con stop 95; el stream cae en la vela 3 y en la 4 el mínimo fue 90
    sesiones = [[Send(tick(3, closed=False)), Drop()], [Send(tick(6, closed=False))]]
    rest = [Reply(body=klines(0, 3)), Reply(body=klines(0, 6, lows={4: 90}))]

    senales = _run_engine(monkeypatch, capsys, sesiones, rest, _Strategy(),
                          open_position={"side": "BUY", "stop": 95.0, "take_profit": 110.0})

    assert [(s["action"], s["reason"]) for s in senales] == [("SELL", "STOP")]
    assert senales[0]["timestamp"] == BASE + 4 * MINUTE  # la vela en la que saltó, no la de hoy
    assert float(senales[0]["price"]) == 95.0


def test_con_la_posicion_a_salvo_no_se_emite_nada_al_volver(monkeypatch, capsys):
    sesiones = [[Send(tick(3, closed=False)), Drop()], [Send(tick(6, closed=False))]]
    rest = [Reply(body=klines(0, 3)), Reply(body=klines(0, 6))]

    senales = _run_engine(monkeypatch, capsys, sesiones, rest, _Strategy(),
                          open_position={"side": "BUY", "stop": 95.0, "take_profit": 110.0})

    assert senales == []


def test_con_un_hueco_en_las_velas_el_motor_no_evalua_la_estrategia(monkeypatch, capsys):
    # el REST no vuelve tras la caída: la vela 4 se perdió y el buffer salta de la 3 a la 5
    sesiones = [[Send(tick(3)), Drop()], [Send(tick(5))]]
    rest = [Reply(body=klines(0, 3))]
    estrategia = _Strategy()

    _run_engine(monkeypatch, capsys, sesiones, rest, estrategia, rest_fallback=Reply(503, ""))

    assert estrategia.calls == 1  # solo la vela 3, antes del hueco
