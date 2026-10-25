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


def _kline(ts: int, close: float = 100.0) -> str:
    c = str(close)
    return json.dumps({"k": {"t": ts, "o": c, "h": c, "l": c, "c": c, "v": "1", "x": True}})


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


def _run(monkeypatch, messages, strategy, sleeps):
    monkeypatch.setattr(engine_rt, "load_strategy_by_path", lambda *a, **k: strategy)
    monkeypatch.setattr(engine_rt.websockets, "connect", lambda url: _FakeWS(messages))

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

    assert sleeps == [5, 10, 20]


def test_non_kline_messages_are_ignored(monkeypatch):
    sleeps: list = []
    messages = [json.dumps({"error": "x"}), _kline(1)]
    strategy = _Strategy()

    with pytest.raises(_Stop):
        _run(monkeypatch, messages, strategy, sleeps)

    assert strategy.calls == 3  # el kline se procesó en cada conexión: sin KeyError antes


# ── Modo de señal: cierre, apertura o ambos (#85) ───────────────────────────

def _tick(open_time: int, closed: bool, price: float = 100.0) -> str:
    p = str(price)
    return json.dumps({"k": {"t": open_time, "o": p, "h": p, "l": p, "c": p, "v": "1", "x": closed}})


class _BuyAlways(_Strategy):
    def should_buy(self, row):
        return True


def _signals_for(monkeypatch, capsys, mode, messages):
    sleeps: list = []
    monkeypatch.setattr(engine_rt, "load_strategy_by_path", lambda *a, **k: _BuyAlways())
    monkeypatch.setattr(engine_rt.websockets, "connect", lambda url: _FakeWS(messages))

    async def fake_sleep(delay):
        sleeps.append(delay)
        raise _Stop

    monkeypatch.setattr(engine_rt.asyncio, "sleep", fake_sleep)
    with pytest.raises(_Stop):
        asyncio.run(engine_rt.run_symbol("BTCUSDT", "1m", "x.py", 1000.0, 0.02, False, signal_mode=mode))
    lines = [ln for ln in capsys.readouterr().out.splitlines() if ln.startswith("SIGNAL")]
    return [json.loads(ln.split("\t", 1)[1])["timestamp"] for ln in lines]


# vela 1000: dos ticks y cierre; vela 2000: primer tick, cierre; vela 3000: primer tick (sin cerrar)
_STREAM = [_tick(1000, False), _tick(1000, False), _tick(1000, True),
           _tick(2000, False), _tick(2000, True), _tick(3000, False)]


def test_modo_close_solo_evalua_las_velas_cerradas(monkeypatch, capsys):
    assert _signals_for(monkeypatch, capsys, "close", _STREAM) == [1000, 2000]


def test_modo_open_evalua_la_primera_lectura_de_cada_vela_nueva(monkeypatch, capsys):
    # la vela 1000 es la primera que se ve tras conectar: no es una apertura real
    assert _signals_for(monkeypatch, capsys, "open", _STREAM) == [2000, 3000]


def test_modo_both_evalua_cierre_y_apertura(monkeypatch, capsys):
    assert _signals_for(monkeypatch, capsys, "both", _STREAM) == [1000, 2000, 3000]


def test_un_modo_desconocido_es_un_error():
    with pytest.raises(ValueError, match="signal_mode"):
        asyncio.run(engine_rt.run_symbol("BTCUSDT", "1m", "x.py", 1000.0, 0.02, False, signal_mode="nunca"))
