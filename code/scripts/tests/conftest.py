"""
Fixtures compartidas para tests de engines IPC.
"""
from __future__ import annotations

import struct
from datetime import datetime, timedelta

import msgpack
import numpy as np
import pandas as pd
import pytest


# =============================================================================
# HELPERS DE FRAMING IPC
# =============================================================================

def _make_framed_request(payload: dict) -> bytes:
    """Empaqueta un payload como MessagePack con framing 4-byte big-endian."""
    body = msgpack.packb(payload, use_bin_type=True)
    return struct.pack(">I", len(body)) + body


# =============================================================================
# FIXTURES DE DATOS DE MERCADO
# =============================================================================

@pytest.fixture
def df_ohlcv_mock() -> pd.DataFrame:
    """DataFrame OHLCV sintético de 200 velas para tests."""
    n = 200
    rng = np.random.default_rng(seed=42)
    base_price = 40_000.0
    returns = rng.normal(0, 0.001, n)
    close = base_price * np.exp(np.cumsum(returns))

    start = datetime(2024, 1, 1)
    timestamps = [
        int((start + timedelta(hours=i)).timestamp() * 1000) for i in range(n)
    ]

    return pd.DataFrame({
        "timestamp": timestamps,
        "open":   close * 0.999,
        "high":   close * 1.002,
        "low":    close * 0.998,
        "close":  close,
        "volume": rng.uniform(100, 1_000, n),
    })


@pytest.fixture
def ohlcv_records(df_ohlcv_mock: pd.DataFrame) -> list[dict]:
    """Lista de dicts OHLCV lista para incluir en payloads IPC."""
    return df_ohlcv_mock.to_dict(orient="records")


# =============================================================================
# FIXTURES DE PAYLOADS IPC
# =============================================================================

@pytest.fixture
def ipc_payload_fetch() -> dict:
    """Payload MessagePack típico para FETCH_REQUEST."""
    return {
        "symbol": "BTCUSDT",
        "timeframe": "1h",
        "since_ms": 1_704_067_200_000,  # 2024-01-01
    }


@pytest.fixture
def ipc_payload_indicators(ohlcv_records: list[dict]) -> dict:
    """Payload MessagePack típico para INDICATORS_REQUEST."""
    return {
        "velas": ohlcv_records,
    }


@pytest.fixture
def framed_fetch_request(ipc_payload_fetch: dict) -> bytes:
    """Frame IPC binario listo para inyectar en stdin de engine_fetch."""
    return _make_framed_request(ipc_payload_fetch)


@pytest.fixture
def framed_indicators_request(ipc_payload_indicators: dict) -> bytes:
    """Frame IPC binario listo para inyectar en stdin de engine_indicators."""
    return _make_framed_request(ipc_payload_indicators)
