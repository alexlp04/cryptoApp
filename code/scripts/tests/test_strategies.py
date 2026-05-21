"""test_strategies.py — Tests unitarios de BaseStrategy y StressTestStrategy."""
from __future__ import annotations

import numpy as np
import pandas as pd
import pytest

from strategies.StressTestStrategy import StressTestStrategy


# ─── Helpers ─────────────────────────────────────────────────────────────────

def _df_mock(n: int = 100) -> pd.DataFrame:
    """DataFrame OHLCV sintético mínimo para tests de estrategia."""
    rng = np.random.default_rng(seed=7)
    close = 20_000.0 + np.cumsum(rng.normal(0, 50, n))
    return pd.DataFrame({
        "timestamp": np.arange(
            1_700_000_000_000,
            1_700_000_000_000 + n * 60_000,
            60_000,
            dtype=np.int64,
        ),
        "open":   close * 0.9995,
        "high":   close * 1.001,
        "low":    close * 0.999,
        "close":  close,
        "volume": rng.uniform(200, 2_000, n),
    })


@pytest.fixture
def strategy() -> StressTestStrategy:
    return StressTestStrategy(capital=10_000.0, risk_per_trade=0.02)


@pytest.fixture
def df_with_indicators(strategy: StressTestStrategy) -> pd.DataFrame:
    return strategy.populate_indicators(_df_mock(100))


# ─── Tests: BaseStrategy — contrato y campos calculados ──────────────────────

class TestBaseStrategyContract:

    def test_should_initialise_capital_as_decimal(self, strategy):
        from decimal import Decimal
        assert strategy.capital == Decimal("10000.0")

    def test_should_initialise_risk_per_trade_as_decimal(self, strategy):
        from decimal import Decimal
        assert strategy.risk_per_trade == Decimal("0.02")

    def test_should_return_class_name_from_get_name(self, strategy):
        assert strategy.get_name() == "StressTestStrategy"

    def test_should_calculate_position_size_correctly(self, strategy):
        from decimal import Decimal
        # 10000 * 0.02 / 50000 = 0.004
        size = strategy.get_position_size(50_000.0)
        assert abs(float(size) - 0.004) < 1e-9

    def test_should_return_warmup_period_at_least_strategy_constant(self, strategy):
        assert strategy.get_warmup_period() >= StressTestStrategy.WARMUP_PERIOD

    def test_should_return_max_open_trades_as_1_by_default(self, strategy):
        assert strategy.max_open_trades() == 1


# ─── Tests: BaseStrategy.get_label ───────────────────────────────────────────

class TestGetLabel:

    def test_should_return_1_for_upward_movement_above_threshold(self, strategy):
        # LABEL_RETURN_THRESHOLD = 0.001 (0.1 %)
        # ret = (100.5 − 100) / 100 = 0.005 > 0.001  →  label 1
        label = strategy.get_label({"close": 100.0}, {"close": 100.5})
        assert label == 1

    def test_should_return_minus1_for_downward_movement_below_threshold(self, strategy):
        # ret = (99.4 − 100) / 100 = −0.006 < −0.001  →  label −1
        label = strategy.get_label({"close": 100.0}, {"close": 99.4})
        assert label == -1

    def test_should_return_0_for_movement_within_threshold(self, strategy):
        # ret = (100.05 − 100) / 100 = 0.0005 < 0.001  →  label 0
        label = strategy.get_label({"close": 100.0}, {"close": 100.05})
        assert label == 0

    def test_should_return_0_when_current_close_is_zero(self, strategy):
        label = strategy.get_label({"close": 0.0}, {"close": 100.0})
        assert label == 0

    def test_should_return_0_for_perfectly_flat_movement(self, strategy):
        label = strategy.get_label({"close": 100.0}, {"close": 100.0})
        assert label == 0

    def test_should_return_1_for_exact_threshold_crossing(self, strategy):
        # LABEL_RETURN_THRESHOLD = 0.004 (0.4%);
        # ret = 0.41% > 0.4% → label 1
        label = strategy.get_label({"close": 100.0}, {"close": 100.41})
        assert label == 1


# ─── Tests: BaseStrategy.should_close ────────────────────────────────────────

class TestShouldClose:

    def test_should_close_when_price_falls_below_stop_loss(self, strategy):
        # stop_loss = 100 * 0.997 = 99.7; price 99.5 ≤ 99.7 → True
        assert strategy.should_close({"close": 99.5}, entry_price=100.0) is True

    def test_should_close_when_price_rises_above_take_profit(self, strategy):
        # take_profit = 100 * 1.003 = 100.3; price 100.5 ≥ 100.3 → True
        assert strategy.should_close({"close": 100.5}, entry_price=100.0) is True

    def test_should_not_close_when_price_is_within_range(self, strategy):
        # 99.7 < 100.1 < 100.3 → False
        assert strategy.should_close({"close": 100.1}, entry_price=100.0) is False

    def test_should_close_exactly_at_stop_loss_level(self, strategy):
        # 99.7 ≤ 99.7 → True
        assert strategy.should_close({"close": 99.7}, entry_price=100.0) is True

    def test_should_close_exactly_at_take_profit_level(self, strategy):
        # 100.3 ≥ 100.3 → True
        assert strategy.should_close({"close": 100.3}, entry_price=100.0) is True


# ─── Tests: StressTestStrategy.populate_indicators ───────────────────────────

class TestPopulateIndicators:

    EXPECTED_FEATURE_COLS = ["ret_1", "ret_3", "ema_gap", "rsi_fast", "vol_z",
                             "atr_norm", "bb_pct", "roc_5"]

    def test_should_add_ret_1_column(self, strategy):
        df = strategy.populate_indicators(_df_mock(50))
        assert "ret_1" in df.columns

    def test_should_add_ret_3_column(self, strategy):
        df = strategy.populate_indicators(_df_mock(50))
        assert "ret_3" in df.columns

    def test_should_add_ema_fast_column(self, strategy):
        df = strategy.populate_indicators(_df_mock(50))
        assert "ema_fast" in df.columns

    def test_should_add_ema_slow_column(self, strategy):
        df = strategy.populate_indicators(_df_mock(50))
        assert "ema_slow" in df.columns

    def test_should_add_rsi_fast_column(self, strategy):
        df = strategy.populate_indicators(_df_mock(50))
        assert "rsi_fast" in df.columns

    def test_should_add_vol_z_column(self, strategy):
        df = strategy.populate_indicators(_df_mock(50))
        assert "vol_z" in df.columns

    def test_should_add_atr_norm_column(self, strategy):
        df = strategy.populate_indicators(_df_mock(50))
        assert "atr_norm" in df.columns

    def test_should_add_bb_pct_column(self, strategy):
        df = strategy.populate_indicators(_df_mock(50))
        assert "bb_pct" in df.columns

    def test_should_add_roc_5_column(self, strategy):
        df = strategy.populate_indicators(_df_mock(50))
        assert "roc_5" in df.columns

    def test_should_preserve_original_row_count(self, strategy):
        df_orig = _df_mock(80)
        df_result = strategy.populate_indicators(df_orig)
        assert len(df_result) == len(df_orig)

    def test_should_not_modify_original_dataframe(self, strategy):
        df_orig = _df_mock(50)
        cols_before = set(df_orig.columns)
        strategy.populate_indicators(df_orig)
        assert set(df_orig.columns) == cols_before

    def test_should_not_contain_nan_in_feature_columns(self, strategy):
        # populate_indicators rellena las feature cols con 0.0 en lugar de NaN/inf
        df = strategy.populate_indicators(_df_mock(100))
        assert not df[self.EXPECTED_FEATURE_COLS].isna().any().any()

    def test_should_not_contain_inf_in_feature_columns(self, strategy):
        df = strategy.populate_indicators(_df_mock(100))
        assert not np.isinf(df[self.EXPECTED_FEATURE_COLS].values).any()


# ─── Tests: should_buy / should_sell ─────────────────────────────────────────

class TestSignals:

    def test_should_buy_when_all_conditions_are_met(self, strategy):
        # ema_gap > 0.001, rsi_fast < 55, ret_1 > 0.0, vol_z > 0.5
        row = {"ema_gap": 0.002, "rsi_fast": 50.0, "ret_1": 0.001, "vol_z": 1.0}
        assert strategy.should_buy(row) is True

    def test_should_not_buy_when_ema_gap_is_negative(self, strategy):
        row = {"ema_gap": -0.001, "rsi_fast": 50.0, "ret_1": 0.0}
        assert strategy.should_buy(row) is False

    def test_should_not_buy_when_rsi_fast_is_above_65(self, strategy):
        row = {"ema_gap": 0.001, "rsi_fast": 70.0, "ret_1": 0.0}
        assert strategy.should_buy(row) is False

    def test_should_not_buy_when_ret_1_is_too_negative(self, strategy):
        row = {"ema_gap": 0.001, "rsi_fast": 50.0, "ret_1": -0.003}
        assert strategy.should_buy(row) is False

    def test_should_not_buy_when_ema_gap_is_zero(self, strategy):
        row = {"ema_gap": 0.0, "rsi_fast": 50.0, "ret_1": 0.0}
        assert strategy.should_buy(row) is False

    def test_should_sell_when_all_conditions_are_met(self, strategy):
        # ema_gap < -0.001, rsi_fast > 45, ret_1 < 0.0, vol_z > 0.5
        row = {"ema_gap": -0.002, "rsi_fast": 60.0, "ret_1": -0.001, "vol_z": 1.0}
        assert strategy.should_sell(row) is True

    def test_should_not_sell_when_ema_gap_is_positive(self, strategy):
        row = {"ema_gap": 0.001, "rsi_fast": 60.0, "ret_1": 0.001}
        assert strategy.should_sell(row) is False

    def test_should_not_sell_when_rsi_fast_is_below_35(self, strategy):
        row = {"ema_gap": -0.001, "rsi_fast": 20.0, "ret_1": 0.001}
        assert strategy.should_sell(row) is False

    def test_should_not_sell_when_ret_1_is_too_positive(self, strategy):
        row = {"ema_gap": -0.001, "rsi_fast": 60.0, "ret_1": 0.003}
        assert strategy.should_sell(row) is False

    def test_should_not_sell_when_ema_gap_is_zero(self, strategy):
        row = {"ema_gap": 0.0, "rsi_fast": 60.0, "ret_1": 0.001}
        assert strategy.should_sell(row) is False


# ─── Tests: get_stop_loss / get_take_profit ───────────────────────────────────

class TestStopTakeProfit:

    def test_should_calculate_stop_loss_at_997_percent(self, strategy):
        # 1000 * 0.997 = 997
        stop = strategy.get_stop_loss(1_000.0, {})
        assert abs(stop - 997.0) < 1e-9

    def test_should_calculate_take_profit_at_1003_percent(self, strategy):
        # 1000 * 1.003 = 1003
        tp = strategy.get_take_profit(1_000.0, {})
        assert abs(tp - 1003.0) < 1e-9

    def test_stop_loss_should_always_be_below_entry_price(self, strategy):
        for price in [100.0, 1_000.0, 50_000.0]:
            assert strategy.get_stop_loss(price, {}) < price

    def test_take_profit_should_always_be_above_entry_price(self, strategy):
        for price in [100.0, 1_000.0, 50_000.0]:
            assert strategy.get_take_profit(price, {}) > price


# ─── Tests: get_feature_columns ──────────────────────────────────────────────

class TestGetFeatureColumns:

    def test_should_return_exactly_8_columns(self, strategy, df_with_indicators):
        cols = strategy.get_feature_columns(df_with_indicators)
        assert len(cols) == 8

    def test_should_not_include_ohlcv_base_columns(self, strategy, df_with_indicators):
        cols = strategy.get_feature_columns(df_with_indicators)
        for base in ("open", "high", "low", "close", "volume"):
            assert base not in cols

    def test_should_not_include_timestamp_column(self, strategy, df_with_indicators):
        cols = strategy.get_feature_columns(df_with_indicators)
        assert "timestamp" not in cols

    def test_should_return_columns_that_exist_in_dataframe(self, strategy, df_with_indicators):
        cols = strategy.get_feature_columns(df_with_indicators)
        for col in cols:
            assert col in df_with_indicators.columns
