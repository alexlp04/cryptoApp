"""conftest.py — Fixtures compartidas para todos los tests de los engines Python."""
from __future__ import annotations

import os
import sys

import numpy as np
import pandas as pd
import pytest

# ── Path setup ──────────────────────────────────────────────────────────────
_TESTS_DIR = os.path.dirname(os.path.abspath(__file__))
_SCRIPTS_DIR = os.path.dirname(_TESTS_DIR)
_CODE_DIR = os.path.dirname(_SCRIPTS_DIR)

for _p in (_CODE_DIR, _SCRIPTS_DIR):
    if _p not in sys.path:
        sys.path.insert(0, _p)


# ── Fixtures ─────────────────────────────────────────────────────────────────

@pytest.fixture
def df_ohlcv_mock() -> pd.DataFrame:
    """DataFrame OHLCV sintético de 200 velas (seed fija para reproducibilidad)."""
    n = 200
    rng = np.random.default_rng(seed=42)
    close = 40_000.0 + np.cumsum(rng.normal(0, 100, n))
    return pd.DataFrame({
        "timestamp": np.arange(
            1_700_000_000_000,
            1_700_000_000_000 + n * 3_600_000,
            3_600_000,
            dtype=np.int64,
        ),
        "open":   close * 0.999,
        "high":   close * 1.002,
        "low":    close * 0.998,
        "close":  close,
        "volume": rng.uniform(100, 1_000, n),
    })


@pytest.fixture
def stress_strategy():
    """Instancia de StressTestStrategy con capital y riesgo por defecto."""
    from strategies.StressTestStrategy import StressTestStrategy
    return StressTestStrategy(capital=10_000.0, risk_per_trade=0.02)
