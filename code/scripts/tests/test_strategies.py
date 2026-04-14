"""
Tests unitarios para BaseStrategy y StressTestStrategy.

Cubre: contrato de la interfaz abstracta, populate_indicators,
should_buy, should_sell, get_feature_columns, get_label.
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

SCRIPTS_DIR = str(Path(__file__).parent.parent)
CODE_DIR = str(Path(__file__).parent.parent.parent)
for _d in (SCRIPTS_DIR, CODE_DIR):
    if _d not in sys.path:
        sys.path.insert(0, _d)

from strategies.BaseStrategy import BaseStrategy
from strategies.StressTestStrategy import StressTestStrategy


# =============================================================================
# FIXTURES
# =============================================================================

@pytest.fixture
def strategy() -> StressTestStrategy:
    return StressTestStrategy(capital=1000, risk_per_trade=0.02)


# =============================================================================
# CONTRATO BaseStrategy (via StressTestStrategy)
# =============================================================================

class TestBaseStrategyContract:
    def test_es_instancia_de_base_strategy(self, strategy):
        assert isinstance(strategy, BaseStrategy)

    def test_populate_indicators_devuelve_dataframe(self, strategy, df_ohlcv_mock):
        result = strategy.populate_indicators(df_ohlcv_mock.copy())
        assert isinstance(result, pd.DataFrame)

    def test_populate_indicators_no_elimina_filas(self, strategy, df_ohlcv_mock):
        result = strategy.populate_indicators(df_ohlcv_mock.copy())
        assert len(result) == len(df_ohlcv_mock)

    def test_populate_indicators_no_modifica_el_df_original(self, strategy, df_ohlcv_mock):
        original_cols = set(df_ohlcv_mock.columns)
        strategy.populate_indicators(df_ohlcv_mock.copy())
        assert set(df_ohlcv_mock.columns) == original_cols

    def test_get_feature_columns_devuelve_lista_no_vacia(self, strategy, df_ohlcv_mock):
        df_enriquecido = strategy.populate_indicators(df_ohlcv_mock.copy())
        cols = strategy.get_feature_columns(df_enriquecido)
        assert isinstance(cols, list)
        assert len(cols) > 0

    def test_get_feature_columns_existen_en_df_enriquecido(self, strategy, df_ohlcv_mock):
        df_enriquecido = strategy.populate_indicators(df_ohlcv_mock.copy())
        feature_cols = strategy.get_feature_columns(df_enriquecido)
        for col in feature_cols:
            assert col in df_enriquecido.columns, f"Feature '{col}' no está en el DataFrame enriquecido"

    def test_should_buy_devuelve_bool(self, strategy, df_ohlcv_mock):
        df = strategy.populate_indicators(df_ohlcv_mock.copy())
        row = df.iloc[-1].to_dict()
        result = strategy.should_buy(row)
        assert isinstance(result, bool)

    def test_should_sell_devuelve_bool(self, strategy, df_ohlcv_mock):
        df = strategy.populate_indicators(df_ohlcv_mock.copy())
        row = df.iloc[-1].to_dict()
        result = strategy.should_sell(row)
        assert isinstance(result, bool)

    def test_get_stop_loss_menor_que_entry_price(self, strategy, df_ohlcv_mock):
        df = strategy.populate_indicators(df_ohlcv_mock.copy())
        row = df.iloc[-1].to_dict()
        entry = float(row["close"])
        sl = strategy.get_stop_loss(entry, row)
        assert sl < entry

    def test_get_take_profit_mayor_que_entry_price(self, strategy, df_ohlcv_mock):
        df = strategy.populate_indicators(df_ohlcv_mock.copy())
        row = df.iloc[-1].to_dict()
        entry = float(row["close"])
        tp = strategy.get_take_profit(entry, row)
        assert tp > entry

    def test_get_label_devuelve_1_o_0_o_menos1(self, strategy, df_ohlcv_mock):
        df = strategy.populate_indicators(df_ohlcv_mock.copy())
        for i in range(min(10, len(df) - 1)):
            row = df.iloc[i].to_dict()
            next_row = df.iloc[i + 1].to_dict()
            label = strategy.get_label(row, next_row)
            assert label in (1, 0, -1), f"Label inesperado: {label}"


# =============================================================================
# StressTestStrategy — indicadores específicos
# =============================================================================

class TestStressTestStrategyIndicators:
    def test_columnas_de_indicadores_existen_tras_populate(self, strategy, df_ohlcv_mock):
        df = strategy.populate_indicators(df_ohlcv_mock.copy())
        expected = {"ret_1", "ret_3", "ema_gap", "rsi_fast", "vol_z",
                    "atr_norm", "bb_pct", "roc_5"}
        for col in expected:
            assert col in df.columns, f"Columna esperada '{col}' no encontrada"

    def test_no_hay_inf_en_features_tras_populate(self, strategy, df_ohlcv_mock):
        df = strategy.populate_indicators(df_ohlcv_mock.copy())
        feature_cols = strategy.get_feature_columns(df)
        for col in feature_cols:
            assert not np.isinf(df[col]).any(), f"Columna '{col}' contiene inf"

    def test_warmup_period_es_entero_positivo(self, strategy):
        assert isinstance(strategy.WARMUP_PERIOD, int)
        assert strategy.WARMUP_PERIOD > 0

    def test_label_return_threshold_es_positivo(self, strategy):
        assert strategy.LABEL_RETURN_THRESHOLD > 0.0

    def test_df_menor_que_warmup_lanza_index_error_de_ta(self, strategy):
        """ATR(14) de la librería `ta` lanza IndexError con < 14 filas — comportamiento esperado."""
        small_df = pd.DataFrame({
            "timestamp": range(5),
            "open": [100.0] * 5,
            "high": [101.0] * 5,
            "low":  [99.0] * 5,
            "close": [100.0] * 5,
            "volume": [500.0] * 5,
        })
        with pytest.raises((IndexError, ValueError)):
            strategy.populate_indicators(small_df)
