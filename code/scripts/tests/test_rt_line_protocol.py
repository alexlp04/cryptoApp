"""test_rt_line_protocol.py — Tests del protocolo de línea del canal RT (NDJSON)."""
from __future__ import annotations

import json

from shared_utils import SIGNAL_PREFIX, build_signal_line


def test_signal_prefix_is_tab_delimited():
    assert SIGNAL_PREFIX == "SIGNAL\t"


def test_build_signal_line_has_prefix_and_valid_json():
    signal = {"symbol": "BTCUSDT", "action": "BUY", "price": "42000", "timestamp": 123}
    line = build_signal_line(signal)

    assert line.startswith(SIGNAL_PREFIX)
    assert json.loads(line[len(SIGNAL_PREFIX):]) == signal


def test_build_signal_line_is_single_line():
    # Ninguna señal debe contener saltos de línea que rompan el framing NDJSON.
    line = build_signal_line({"symbol": "ETHUSDT", "action": "SELL"})
    assert "\n" not in line
