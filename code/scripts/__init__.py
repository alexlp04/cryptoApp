"""
Motores de cálculo para trading: fetch, indicadores, backtest,
optimización, entrenamiento y trading en tiempo real.

Cada motor implementa el protocolo IPC MessagePack con framing
4-byte big-endian definido en ipc_protocol.py.
"""
from __future__ import annotations

import sys
import os

# Añadir el directorio al path para que ipc_protocol sea importable directamente
_SCRIPTS_DIR = os.path.dirname(__file__)
if _SCRIPTS_DIR not in sys.path:
    sys.path.insert(0, _SCRIPTS_DIR)

from ipc_protocol import read_request_payload, write_response, write_error  # noqa: E402

__version__ = "1.0.0"

__all__ = [
    "read_request_payload",
    "write_response",
    "write_error",
]
