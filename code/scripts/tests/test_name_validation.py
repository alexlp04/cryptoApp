"""test_name_validation.py — Los nombres que acaban en rutas de fichero se validan en la frontera de cada engine."""
from __future__ import annotations

import pytest
from shared_utils import require_name, require_symbol, require_timeframe


@pytest.mark.parametrize("malo", ["../../X", "BTC/USDT", "btcusdt", "A", "BTC USDT", "B" * 21, "", None, 5])
def test_simbolos_peligrosos_se_rechazan(malo):
    with pytest.raises(ValueError, match="Símbolo"):
        require_symbol(malo)


def test_simbolos_normales_pasan():
    assert require_symbol("BTCUSDT") == "BTCUSDT"
    assert require_symbol("1000SATSUSDT") == "1000SATSUSDT"


@pytest.mark.parametrize("malo", ["../x", "a/b", "a\\b", "..", "x..y", ".oculto", "", "a" * 65, None])
def test_nombres_peligrosos_se_rechazan(malo):
    with pytest.raises(ValueError, match="modelo"):
        require_name(malo, "modelo")


def test_nombres_normales_pasan():
    for ok in ("xgboost", "random_forest", "RSISMAStrategy", "mi-modelo_v1.2"):
        assert require_name(ok, "modelo") == ok


def test_timeframes():
    for ok in ("1m", "15m", "1h", "4h", "1d", "1w", "1M"):
        assert require_timeframe(ok) == ok
    for malo in ("1", "h", "../1h", "100h", "1H ", None):
        with pytest.raises(ValueError, match="Timeframe"):
            require_timeframe(malo)
