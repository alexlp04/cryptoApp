"""test_engine_rt.py — Buffer de velas y deduplicación de señales del motor RT."""
from __future__ import annotations

from collections import deque

from engine_rt import _buffer_size, _upsert_candle


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
