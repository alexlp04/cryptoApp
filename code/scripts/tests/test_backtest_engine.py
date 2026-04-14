"""
Tests unitarios para backtest_engine.py.

Cubre: _open_long, _close_long, _generate_empty_stats, run_backtest,
run_backtest_with_predictions, _calculate_backtest_stats.
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

SCRIPTS_DIR = str(Path(__file__).parent.parent)
STRATEGIES_DIR = str(Path(__file__).parent.parent.parent / "strategies")
for _d in (SCRIPTS_DIR, STRATEGIES_DIR):
    if _d not in sys.path:
        sys.path.insert(0, _d)

from backtest_engine import (
    _close_long,
    _open_long,
    run_backtest,
    run_backtest_with_predictions,
)
from StressTestStrategy import StressTestStrategy


# =============================================================================
# FIXTURES
# =============================================================================

@pytest.fixture
def strategy():
    return StressTestStrategy(capital=1000, risk_per_trade=0.02)


@pytest.fixture
def df_200_velas(df_ohlcv_mock) -> pd.DataFrame:
    """DataFrame de 200 velas con nombre de columnas compatibles con run_backtest."""
    return df_ohlcv_mock.copy()


# =============================================================================
# _open_long
# =============================================================================

class TestOpenLong:
    def test_devuelve_tamanio_posicion_correcto(self):
        # capital=1000, risk=0.02 → capital*risk=20; price=40000 → size=0.0005
        size = _open_long(capital=1000.0, risk_per_trade=0.02, price=40_000.0)
        assert pytest.approx(size, rel=1e-9) == 0.0005

    def test_riesgo_cero_devuelve_cero(self):
        assert _open_long(capital=1000.0, risk_per_trade=0.0, price=50_000.0) == 0.0

    def test_capital_grande(self):
        size = _open_long(capital=100_000.0, risk_per_trade=0.01, price=25_000.0)
        assert size == pytest.approx(0.04, rel=1e-9)


# =============================================================================
# _close_long
# =============================================================================

class TestCloseLong:
    def test_trade_ganador_incrementa_capital(self):
        cap, wins, losses, pos_pnl, neg_pnl, peak, dd, pnl = _close_long(
            capital=1000.0, position_size=0.001,
            entry_price=40_000.0, exit_price=42_000.0,
            op_ganadas=0, op_perdidas=0,
            pos_pnl=0.0, neg_pnl=0.0,
            peak_capital=1000.0, max_drawdown=0.0,
        )
        assert pnl == pytest.approx(2.0, rel=1e-9)
        assert cap == pytest.approx(1002.0, rel=1e-9)
        assert wins == 1
        assert losses == 0
        assert pos_pnl == pytest.approx(2.0, rel=1e-9)
        assert neg_pnl == 0.0

    def test_trade_perdedor_reduce_capital_y_actualiza_drawdown(self):
        cap, wins, losses, pos_pnl, neg_pnl, peak, dd, pnl = _close_long(
            capital=1000.0, position_size=0.001,
            entry_price=40_000.0, exit_price=38_000.0,
            op_ganadas=0, op_perdidas=0,
            pos_pnl=0.0, neg_pnl=0.0,
            peak_capital=1000.0, max_drawdown=0.0,
        )
        assert pnl == pytest.approx(-2.0, rel=1e-9)
        assert cap == pytest.approx(998.0, rel=1e-9)
        assert losses == 1
        assert wins == 0
        assert neg_pnl == pytest.approx(2.0, rel=1e-9)
        assert dd == pytest.approx(2.0, rel=1e-9)  # peak(1000) - capital(998)

    def test_trade_neutral_no_cuenta_como_ganado_ni_perdido(self):
        _, wins, losses, _, _, _, _, pnl = _close_long(
            capital=1000.0, position_size=0.001,
            entry_price=40_000.0, exit_price=40_000.0,
            op_ganadas=0, op_perdidas=0,
            pos_pnl=0.0, neg_pnl=0.0,
            peak_capital=1000.0, max_drawdown=0.0,
        )
        assert pnl == 0.0
        assert wins == 0
        assert losses == 0

    def test_peak_capital_se_actualiza_si_capital_supera_pico(self):
        _, _, _, _, _, peak, _, _ = _close_long(
            capital=1000.0, position_size=0.001,
            entry_price=40_000.0, exit_price=50_000.0,
            op_ganadas=0, op_perdidas=0,
            pos_pnl=0.0, neg_pnl=0.0,
            peak_capital=1000.0, max_drawdown=0.0,
        )
        assert peak == pytest.approx(1010.0, rel=1e-9)


# =============================================================================
# _generate_empty_stats
# =============================================================================

class TestGenerateEmptyStats:
    def test_devuelve_dict_con_claves_obligatorias(self, strategy):
        from backtest_engine import _generate_empty_stats
        stats = _generate_empty_stats("BTCUSDT", strategy)
        required = {"op_totales", "op_ganadas", "op_perdidas", "win_rate",
                    "profit_factor", "max_drawdown", "retorno_acumulado",
                    "retorno_total"}
        assert required.issubset(stats.keys())

    def test_op_totales_es_cero(self, strategy):
        from backtest_engine import _generate_empty_stats
        stats = _generate_empty_stats("BTCUSDT", strategy)
        assert stats["op_totales"] == 0

    def test_win_rate_es_cero(self, strategy):
        from backtest_engine import _generate_empty_stats
        stats = _generate_empty_stats("BTCUSDT", strategy)
        assert stats["win_rate"] == 0.0


# =============================================================================
# run_backtest
# =============================================================================

class TestRunBacktest:
    def test_devuelve_tupla_count_dict(self, df_200_velas, strategy):
        result = run_backtest(strategy, df_200_velas, "BTCUSDT")
        assert isinstance(result, tuple)
        assert len(result) == 2
        count, stats = result
        assert isinstance(count, int)
        assert isinstance(stats, dict)

    def test_op_totales_en_stats_es_entero_no_negativo(self, df_200_velas, strategy):
        _, stats = run_backtest(strategy, df_200_velas, "BTCUSDT")
        assert isinstance(stats["op_totales"], int)
        assert stats["op_totales"] >= 0

    def test_win_rate_entre_0_y_100(self, df_200_velas, strategy):
        _, stats = run_backtest(strategy, df_200_velas, "BTCUSDT")
        assert 0.0 <= stats["win_rate"] <= 100.0

    def test_df_vacio_devuelve_count_cero(self, strategy):
        df_empty = pd.DataFrame(columns=["timestamp", "open", "high", "low", "close", "volume"])
        count, stats = run_backtest(strategy, df_empty, "BTCUSDT")
        assert count == 0
        assert stats["op_totales"] == 0

    def test_max_drawdown_no_negativo(self, df_200_velas, strategy):
        _, stats = run_backtest(strategy, df_200_velas, "BTCUSDT")
        assert stats["max_drawdown"] >= 0.0


# =============================================================================
# run_backtest_with_predictions
# =============================================================================

class TestRunBacktestWithPredictions:
    def _make_all_buy_predictions(self, df: pd.DataFrame) -> np.ndarray:
        return np.ones(len(df), dtype=int)

    def _make_all_hold_predictions(self, df: pd.DataFrame) -> np.ndarray:
        return np.zeros(len(df), dtype=int)

    def test_devuelve_dict_con_estadisticas(self, df_200_velas, strategy):
        preds = self._make_all_hold_predictions(df_200_velas)
        result = run_backtest_with_predictions(strategy, df_200_velas, preds, "BTCUSDT")
        assert isinstance(result, dict)
        assert "op_totales" in result

    def test_predicciones_vacias_devuelve_stats_vacias(self, strategy):
        df_empty = pd.DataFrame(columns=["timestamp", "open", "high", "low", "close", "volume"])
        preds = np.array([], dtype=int)
        result = run_backtest_with_predictions(strategy, df_empty, preds, "BTCUSDT")
        assert result["op_totales"] == 0

    def test_predicciones_y_df_tienen_misma_longitud(self, df_200_velas, strategy):
        preds = self._make_all_buy_predictions(df_200_velas)
        assert len(preds) == len(df_200_velas)
        result = run_backtest_with_predictions(strategy, df_200_velas, preds, "BTCUSDT")
        assert result is not None
