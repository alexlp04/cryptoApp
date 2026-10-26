"""test_engine_rt.py — Buffer de velas y deduplicación de señales del motor RT."""
from __future__ import annotations

from collections import deque

from engine_rt import _buffer_size
from shared_utils import upsert_candle as _upsert_candle


def _candle(ts: int, close: float) -> dict:
    return {"timestamp": ts, "open": close, "high": close, "low": close, "close": close, "volume": 1.0}


def test_upsert_replaces_in_progress_candle():
    # Cada tick de la vela abierta llega con el mismo open_time: debe sustituir, no añadir (#58).
    buf: deque = deque(maxlen=10)
    for tick in range(30):
        _upsert_candle(buf, _candle(1000, 100.0 + tick))

    assert len(buf) == 1
    assert buf[-1]["close"] == 129.0


def test_upsert_appends_new_candle():
    buf: deque = deque(maxlen=10)
    _upsert_candle(buf, _candle(1000, 1.0))
    _upsert_candle(buf, _candle(2000, 2.0))

    assert [c["timestamp"] for c in buf] == [1000, 2000]


def test_buffer_holds_at_least_the_strategy_warmup():
    # Con WARMUP_PERIOD=300 y max_candles=100 el buffer nunca llegaba al mínimo (#57).
    assert _buffer_size(max_candles=100, warmup=300) >= 300
    assert _buffer_size(max_candles=100, warmup=20) == 100


# ── Reconexión y errores de estrategia (#63) ────────────────────────────────

import asyncio
import json

import engine_rt
import pytest
import rt_stream

MINUTE_MS = 60_000  # los timestamps de los tests son índices de vela de 1m: el motor exige velas contiguas


def _kline(ts: int, close: float = 100.0) -> str:
    c = str(close)
    return json.dumps({"k": {"t": ts * MINUTE_MS, "o": c, "h": c, "l": c, "c": c, "v": "1", "x": True}})


class _FakeWS:
    def __init__(self, messages):
        self._messages = messages

    async def __aenter__(self):
        return self

    async def __aexit__(self, *exc):
        return False

    def __aiter__(self):
        async def gen():
            for m in self._messages:
                yield m
        return gen()


class _Stop(Exception):
    pass


class _Strategy:
    WARMUP_PERIOD = 1

    def __init__(self, fail: bool = False):
        self.fail = fail
        self.calls = 0

    def populate_indicators(self, df):
        self.calls += 1
        if self.fail:
            raise ValueError("bug de estrategia")
        return df

    def should_buy(self, row):
        return False

    def should_sell(self, row):
        return False


@pytest.fixture(autouse=True)
def _sin_red(monkeypatch):
    """Los tests no tocan el REST de Binance: el sembrado devuelve vacío salvo que el test lo cambie."""
    monkeypatch.setattr(engine_rt, "fetch_recent_klines", lambda *a, **k: [])


def _run(monkeypatch, messages, strategy, sleeps):
    monkeypatch.setattr(engine_rt, "load_strategy_by_path", lambda *a, **k: strategy)
    monkeypatch.setattr(rt_stream.websockets, "connect", lambda url: _FakeWS(messages))

    async def fake_sleep(delay):
        sleeps.append(delay)
        if len(sleeps) >= 3:
            raise _Stop

    monkeypatch.setattr(engine_rt.asyncio, "sleep", fake_sleep)
    return asyncio.run(engine_rt.run_symbol("BTCUSDT", "1m", "x.py", 1000.0, 0.02, False))


def test_persistent_strategy_error_terminates_instead_of_reconnecting(monkeypatch):
    sleeps: list = []
    messages = [_kline(i) for i in range(engine_rt.MAX_STRATEGY_ERRORS)]

    with pytest.raises(engine_rt.StrategyFailure):
        _run(monkeypatch, messages, _Strategy(fail=True), sleeps)

    assert sleeps == []  # no hubo reconexiones


def test_clean_close_waits_before_reconnecting_with_growing_backoff(monkeypatch):
    sleeps: list = []

    with pytest.raises(_Stop):
        _run(monkeypatch, [], _Strategy(), sleeps)  # el stream cierra sin mensajes

    assert sleeps == [1, 2, 4]


def test_non_kline_messages_are_ignored(monkeypatch):
    sleeps: list = []
    messages = [json.dumps({"error": "x"}), _kline(1)]
    strategy = _Strategy()

    with pytest.raises(_Stop):
        _run(monkeypatch, messages, strategy, sleeps)

    assert strategy.calls == 3  # el kline se procesó en cada conexión: sin KeyError antes


# ── Modo de señal: cierre, apertura o ambos (#85) ───────────────────────────

def _tick(open_time: int, closed: bool, price: float = 100.0) -> str:
    """Tick de la vela `open_time` (en milésimas de vela: 1000 = vela 1, 2000 = vela 2...)."""
    p = str(price)
    return json.dumps({"k": {"t": open_time * 60, "o": p, "h": p, "l": p, "c": p, "v": "1", "x": closed}})


class _BuyAlways(_Strategy):
    """Alterna compra y venta en cada evaluación: cada evaluación emite una señal."""

    def __init__(self):
        super().__init__()
        self.evaluaciones = 0

    def populate_indicators(self, df):
        self.evaluaciones += 1
        return df

    def should_buy(self, row):
        return self.evaluaciones % 2 == 1

    def should_sell(self, row):
        return self.evaluaciones % 2 == 0

    def get_stop_loss(self, entry_price, row):
        return entry_price * 0.5

    def get_take_profit(self, entry_price, row):
        return entry_price * 2


def _signals_for(monkeypatch, capsys, mode, messages):
    sleeps: list = []
    monkeypatch.setattr(engine_rt, "load_strategy_by_path", lambda *a, **k: _BuyAlways())
    monkeypatch.setattr(rt_stream.websockets, "connect", lambda url: _FakeWS(messages))

    async def fake_sleep(delay):
        sleeps.append(delay)
        raise _Stop

    monkeypatch.setattr(engine_rt.asyncio, "sleep", fake_sleep)
    with pytest.raises(_Stop):
        asyncio.run(engine_rt.run_symbol("BTCUSDT", "1m", "x.py", 1000.0, 0.02, False, signal_mode=mode))
    lines = [ln for ln in capsys.readouterr().out.splitlines() if ln.startswith("SIGNAL")]
    return [json.loads(ln.split("\t", 1)[1])["timestamp"] // 60 for ln in lines]


# vela 1000: dos ticks y cierre; vela 2000: primer tick, cierre; vela 3000: primer tick (sin cerrar)
_STREAM = [_tick(1000, False), _tick(1000, False), _tick(1000, True),
           _tick(2000, False), _tick(2000, True), _tick(3000, False)]


def test_modo_close_solo_evalua_las_velas_cerradas(monkeypatch, capsys):
    assert _signals_for(monkeypatch, capsys, "close", _STREAM) == [1000, 2000]


def test_modo_open_evalua_la_primera_lectura_de_cada_vela_nueva(monkeypatch, capsys):
    # la vela 1000 es la primera que se ve tras conectar: no es una apertura real
    assert _signals_for(monkeypatch, capsys, "open", _STREAM) == [2000, 3000]


def test_modo_both_evalua_cierre_y_apertura(monkeypatch, capsys):
    # apertura de 2000 (SELL) y cierre de 2000 (BUY) son dos señales distintas de la misma vela
    assert _signals_for(monkeypatch, capsys, "both", _STREAM) == [1000, 2000, 2000, 3000]


def test_un_modo_desconocido_es_un_error():
    with pytest.raises(ValueError, match="signal_mode"):
        asyncio.run(engine_rt.run_symbol("BTCUSDT", "1m", "x.py", 1000.0, 0.02, False, signal_mode="nunca"))


# ── Sembrado y huecos al (re)conectar (#63) ─────────────────────────────────

def test_al_conectar_el_buffer_se_siembra_y_la_estrategia_no_espera_al_calentamiento(monkeypatch):
    sleeps: list = []
    strategy = _Strategy()
    strategy.WARMUP_PERIOD = 5
    historico = [{"timestamp": t * MINUTE_MS, "open": 1.0, "high": 1.0, "low": 1.0, "close": 1.0, "volume": 1.0}
                 for t in range(1, 6)]
    monkeypatch.setattr(engine_rt, "fetch_recent_klines", lambda *a, **k: list(historico))

    with pytest.raises(_Stop):
        _run(monkeypatch, [_kline(6)], strategy, sleeps)

    assert strategy.calls == 3  # sin sembrar, 1 vela < WARMUP_PERIOD=5 y no se evaluaba nunca


def test_si_el_rest_falla_se_sigue_con_el_stream(monkeypatch):
    sleeps: list = []
    strategy = _Strategy()

    def roto(*a, **k):
        raise OSError("sin red")

    monkeypatch.setattr(engine_rt, "fetch_recent_klines", roto)

    with pytest.raises(_Stop):
        _run(monkeypatch, [_kline(1)], strategy, sleeps)

    assert strategy.calls == 3  # el stream se procesó igual en cada conexión


# ── Stop y objetivo protegen la posición (gestión de riesgo) ────────────────

def _signals(monkeypatch, capsys, strategy, messages, open_position=None):
    monkeypatch.setattr(engine_rt, "load_strategy_by_path", lambda *a, **k: strategy)
    monkeypatch.setattr(rt_stream.websockets, "connect", lambda url: _FakeWS(messages))

    async def fake_sleep(delay):
        raise _Stop

    monkeypatch.setattr(engine_rt.asyncio, "sleep", fake_sleep)
    with pytest.raises(_Stop):
        asyncio.run(engine_rt.run_symbol("BTCUSDT", "1m", "x.py", 1000.0, 0.02, False,
                                         open_position=open_position))
    return [json.loads(ln.split("\t", 1)[1]) for ln in capsys.readouterr().out.splitlines()
            if ln.startswith("SIGNAL")]


class _SoloCompra(_Strategy):
    def __init__(self, stop=95.0, tp=110.0):
        super().__init__()
        self._levels = (stop, tp)

    def should_buy(self, row):
        return True

    def get_stop_loss(self, entry_price, row):
        return self._levels[0]

    def get_take_profit(self, entry_price, row):
        return self._levels[1]


def test_el_buy_lleva_stop_y_objetivo(monkeypatch, capsys):
    senales = _signals(monkeypatch, capsys, _SoloCompra(), [_tick(1000, True, 100.0)])

    assert senales[0]["action"] == "BUY"
    assert senales[0]["stop_loss"] == "95.0" and senales[0]["take_profit"] == "110.0"


def test_sin_stop_valido_no_se_compra(monkeypatch, capsys):
    senales = _signals(monkeypatch, capsys, _SoloCompra(stop=120.0), [_tick(1000, True, 100.0)])

    assert senales == []


def test_si_el_precio_toca_el_stop_sale_aunque_la_vela_no_haya_cerrado(monkeypatch, capsys):
    mensajes = [_tick(1000, True, 100.0), _tick(2000, False, 94.0)]

    senales = _signals(monkeypatch, capsys, _SoloCompra(), mensajes)

    assert [(s["action"], s["reason"]) for s in senales] == [("BUY", "SIGNAL"), ("SELL", "STOP")]
    assert senales[1]["price"] == "94.0"


def test_el_objetivo_sale_a_su_precio(monkeypatch, capsys):
    mensajes = [_tick(1000, True, 100.0), _tick(2000, False, 112.0)]

    senales = _signals(monkeypatch, capsys, _SoloCompra(), mensajes)

    assert (senales[1]["action"], senales[1]["reason"], senales[1]["price"]) == ("SELL", "TARGET", "110.0")


def test_con_posicion_abierta_no_se_vuelve_a_comprar(monkeypatch, capsys):
    mensajes = [_tick(1000, True, 100.0), _tick(2000, True, 101.0)]

    senales = _signals(monkeypatch, capsys, _SoloCompra(), mensajes)

    assert [s["action"] for s in senales] == ["BUY"]


def test_una_posicion_abierta_al_arrancar_tambien_esta_protegida(monkeypatch, capsys):
    posicion = {"stop": 95.0, "take_profit": 110.0}

    senales = _signals(monkeypatch, capsys, _Strategy(), [_tick(1000, False, 90.0)], open_position=posicion)

    assert [(s["action"], s["reason"]) for s in senales] == [("SELL", "STOP")]


class _SoloCorto(_SoloCompra):
    allow_short = True

    def should_buy(self, row):
        return False

    def should_short(self, row):
        return True

    def should_cover(self, row):
        return False

    def get_short_stop_loss(self, entry_price, row):
        return 105.0

    def get_short_take_profit(self, entry_price, row):
        return 90.0


def test_el_short_lleva_stop_por_encima_y_se_cubre_con_cover(monkeypatch, capsys):
    mensajes = [_tick(1000, True, 100.0), _tick(2000, False, 106.0)]

    senales = _signals(monkeypatch, capsys, _SoloCorto(), mensajes)

    assert [(s["action"], s["reason"]) for s in senales] == [("SHORT", "SIGNAL"), ("COVER", "STOP")]
    assert senales[0]["stop_loss"] == "105.0" and senales[0]["take_profit"] == "90.0"


def test_el_objetivo_de_un_corto_se_cubre_a_su_precio(monkeypatch, capsys):
    mensajes = [_tick(1000, True, 100.0), _tick(2000, False, 85.0)]

    senales = _signals(monkeypatch, capsys, _SoloCorto(), mensajes)

    assert (senales[1]["action"], senales[1]["reason"], senales[1]["price"]) == ("COVER", "TARGET", "90.0")


def test_sin_allow_short_no_se_emite_short(monkeypatch, capsys):
    estrategia = _SoloCorto()
    estrategia.allow_short = False

    assert _signals(monkeypatch, capsys, estrategia, [_tick(1000, True, 100.0)]) == []


class _GiroLargoACorto(_SoloCorto):
    """Con un largo abierto, la venta llega junto a la señal de corto: se cierra y se gira."""

    def should_sell(self, row):
        return True


def test_un_giro_de_largo_a_corto_emite_sell_y_short_seguidos(monkeypatch, capsys):
    posicion = {"stop": 90.0, "take_profit": 120.0, "side": "LONG"}

    senales = _signals(monkeypatch, capsys, _GiroLargoACorto(), [_tick(1000, True, 100.0)], open_position=posicion)

    assert [s["action"] for s in senales] == ["SELL", "SHORT"]
    assert senales[1]["stop_loss"] == "105.0"
