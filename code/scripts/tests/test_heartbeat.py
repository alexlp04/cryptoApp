"""test_heartbeat.py — Tests del heartbeat de liveness del canal RT."""
from __future__ import annotations

import asyncio
import json

import pytest
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


def test_emit_heartbeats_prints_periodically(capsys, monkeypatch):
    """emit_heartbeats emite un heartbeat por cada intervalo cumplido.

    No se mide tiempo real a propósito: la versión anterior dormía 35 ms esperando
    al menos 2 latidos de 10 ms y fallaba de forma intermitente, porque la
    granularidad del temporizador de Windows (~15 ms) y la carga de la máquina
    hacían que solo diese tiempo a uno. Aquí se sustituye la espera por un doble
    que cuenta invocaciones, así el resultado no depende del reloj.
    """
    esperas: list[float] = []

    async def fake_sleep(seconds: float) -> None:
        esperas.append(seconds)
        if len(esperas) >= 3:
            raise asyncio.CancelledError

    monkeypatch.setattr(asyncio, "sleep", fake_sleep)

    with pytest.raises(asyncio.CancelledError):
        asyncio.run(emit_heartbeats(interval_seconds=0.01))

    # Dos esperas completas → dos latidos; la tercera corta el bucle antes de imprimir.
    out_lines = [ln for ln in capsys.readouterr().out.splitlines() if ln.startswith("HEARTBEAT")]
    assert len(out_lines) == 2
    for ln in out_lines:
        assert json.loads(ln[len(HEARTBEAT_PREFIX):]) == {"type": "heartbeat"}

    # El intervalo solicitado se respeta en cada iteración.
    assert esperas == [0.01, 0.01, 0.01]
