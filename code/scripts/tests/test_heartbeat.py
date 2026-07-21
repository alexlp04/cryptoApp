"""test_heartbeat.py — Tests del heartbeat de liveness del canal RT."""
from __future__ import annotations

import asyncio
import json

from shared_utils import HEARTBEAT_PREFIX, build_heartbeat_line, emit_heartbeats


def test_heartbeat_line_has_prefix():
    """La línea debe empezar con el prefijo HEARTBEAT + tab."""
    line = build_heartbeat_line()
    assert line.startswith(HEARTBEAT_PREFIX)
    assert HEARTBEAT_PREFIX == "HEARTBEAT\t"


def test_heartbeat_payload_is_valid_json():
    """El cuerpo tras el prefijo debe ser JSON válido con type=heartbeat."""
    line = build_heartbeat_line()
    payload = json.loads(line[len(HEARTBEAT_PREFIX):])
    assert payload == {"type": "heartbeat"}


def test_heartbeat_line_is_single_line():
    """No debe contener saltos de línea que rompan el framing por líneas."""
    assert "\n" not in build_heartbeat_line()


def test_emit_heartbeats_prints_periodically(capsys):
    """emit_heartbeats debe imprimir un heartbeat por intervalo en stdout."""

    async def run():
        task = asyncio.create_task(emit_heartbeats(interval_seconds=0.01))
        await asyncio.sleep(0.035)  # ~3 intervalos
        task.cancel()
        try:
            await task
        except asyncio.CancelledError:
            pass

    asyncio.run(run())

    out_lines = [ln for ln in capsys.readouterr().out.splitlines() if ln.startswith("HEARTBEAT")]
    assert len(out_lines) >= 2
    for ln in out_lines:
        assert json.loads(ln[len(HEARTBEAT_PREFIX):]) == {"type": "heartbeat"}
