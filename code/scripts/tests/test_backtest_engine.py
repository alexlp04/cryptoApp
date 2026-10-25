"""test_backtest_engine.py — Tests unitarios del motor compartido de backtesting."""
from __future__ import annotations

import os

import numpy as np
import pandas as pd
import pytest
from backtest_engine import (
    _calculate_backtest_stats,
    _close_long,
    _generate_empty_stats,
    crear_carpeta_estrategia,
    guardar_trade_a_csv,
    run_backtest,
    run_backtest_with_predictions,
)

# ─── Stubs de estrategia ──────────────────────────────────────────────────────

class _NeverTradeStrategy:
    """Stub: nunca emite señales de compra ni venta."""
    capital = 5_000.0
    risk_per_trade = 0.01
    timeframe = "4h"

    def populate_indicators(self, df: pd.DataFrame) -> pd.DataFrame:
        return df

    def should_buy(self, row: dict) -> bool:
        return False

    def should_sell(self, row: dict) -> bool:
        return False

    def should_close(self, row: dict, entry_price: float) -> bool:
        return False

    def get_stop_loss(self, entry_price: float, row: dict) -> float:
        return entry_price * 0.5

    def get_take_profit(self, entry_price: float, row: dict) -> float:
        return entry_price * 10


class _BuyOnceStrategy:
    """Stub: compra en la primera vela y vende en la segunda."""
    capital = 10_000.0
    risk_per_trade = 0.02
    timeframe = "1h"
    _bought = False

    def populate_indicators(self, df: pd.DataFrame) -> pd.DataFrame:
        self._bought = False
        return df

    def should_buy(self, row: dict) -> bool:
        if not self._bought:
            self._bought = True
            return True
        return False

    def should_sell(self, row: dict) -> bool:
        return self._bought

    def should_close(self, row: dict, entry_price: float) -> bool:
        return False

    def get_stop_loss(self, entry_price: float, row: dict) -> float:
        return entry_price * 0.5

    def get_take_profit(self, entry_price: float, row: dict) -> float:
        return entry_price * 10


# ─── Helper ───────────────────────────────────────────────────────────────────

def _df_mock(n: int = 50) -> pd.DataFrame:
    rng = np.random.default_rng(seed=0)
    close = 30_000.0 + np.cumsum(rng.normal(0, 50, n))
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
        "volume": rng.uniform(100, 500, n),
    })


# ─── Tests: _close_long ──────────────────────────────────────────────────────

class TestCloseLong:

    def _call(self, entry: float, exit_: float, capital: float = 10_000.0,
              pos_size: float = 0.1) -> tuple:
        return _close_long(
            capital=capital,
            position_size=pos_size,
            entry_price=entry,
            exit_price=exit_,
            op_ganadas=0,
            op_perdidas=0,
            pos_pnl=0.0,
            neg_pnl=0.0,
            peak_capital=capital,
            max_drawdown=0.0,
        )

    def test_should_increment_op_ganadas_on_profit(self):
        _, op_ganadas, op_perdidas, *_ = self._call(100.0, 110.0)
        assert op_ganadas == 1
        assert op_perdidas == 0

    def test_should_increment_op_perdidas_on_loss(self):
        _, op_ganadas, op_perdidas, *_ = self._call(100.0, 90.0)
        assert op_ganadas == 0
        assert op_perdidas == 1

    def test_should_not_change_counters_on_breakeven(self):
        _, op_ganadas, op_perdidas, *_ = self._call(100.0, 100.0)
        assert op_ganadas == 0
        assert op_perdidas == 0

    def test_should_add_pnl_to_capital_on_profit(self):
        # pnl = 0.1 * (110 − 100) = 1.0
        capital, *_ = self._call(100.0, 110.0)
        assert abs(capital - 10_001.0) < 1e-9

    def test_should_subtract_pnl_from_capital_on_loss(self):
        # pnl = 0.1 * (90 − 100) = −1.0
        capital, *_ = self._call(100.0, 90.0)
        assert abs(capital - 9_999.0) < 1e-9

    def test_should_track_max_drawdown_on_loss(self):
        # Given: capital empieza en 10000, cae 1
        *_, _peak_capital, max_drawdown, _pnl = self._call(100.0, 90.0)
        assert max_drawdown > 0.0

    def test_should_accumulate_pos_pnl_on_profit(self):
        _, _, _, pos_pnl, *_ = self._call(100.0, 110.0)
        assert pos_pnl > 0.0

    def test_should_accumulate_neg_pnl_on_loss(self):
        _, _, _, _, neg_pnl, *_ = self._call(100.0, 90.0)
        assert neg_pnl > 0.0

    def test_should_return_pnl_as_final_element(self):
        *_, pnl = self._call(100.0, 110.0)
        # pnl = 0.1 * 10 = 1.0
        assert abs(pnl - 1.0) < 1e-9


# ─── Tests: crear_carpeta_estrategia ─────────────────────────────────────────

class TestCrearCarpetaEstrategia:

    def test_should_create_directory(self, tmp_path, monkeypatch):
        # Given
        import backtest_engine
        monkeypatch.setattr(backtest_engine, "results_root", str(tmp_path))
        # When
        path = crear_carpeta_estrategia("MiEstrategia")
        # Then
        assert os.path.isdir(path)

    def test_should_return_path_ending_with_strategy_name(self, tmp_path, monkeypatch):
        import backtest_engine
        monkeypatch.setattr(backtest_engine, "results_root", str(tmp_path))
        path = crear_carpeta_estrategia("TestStrat")
        assert path.endswith("TestStrat")

    def test_should_be_idempotent_when_directory_already_exists(self, tmp_path, monkeypatch):
        import backtest_engine
        monkeypatch.setattr(backtest_engine, "results_root", str(tmp_path))
        # When: llamada doble sin excepción
        crear_carpeta_estrategia("Idem")
        crear_carpeta_estrategia("Idem")  # no debe lanzar


# ─── Tests: guardar_trade_a_csv ───────────────────────────────────────────────

class TestGuardarTradeACsv:

    def _trade(self, side: str = "BUY") -> dict:
        return {"side": side, "price": 50_000.0, "timestamp": 1_700_000_000_000,
                "pnl": 10.0, "capital": 10_010.0}

    def test_should_create_csv_file_on_first_write(self, tmp_path):
        guardar_trade_a_csv(str(tmp_path), "BTCUSDT", "1h", self._trade())
        assert any(f.suffix == ".csv" for f in tmp_path.iterdir())

    def test_should_use_symbol_and_timeframe_in_filename(self, tmp_path):
        guardar_trade_a_csv(str(tmp_path), "ETHUSDT", "4h", self._trade())
        expected = tmp_path / "ETHUSDT-4h-trades_backtest.csv"
        assert expected.exists()

    def test_should_write_header_row_on_first_write(self, tmp_path):
        guardar_trade_a_csv(str(tmp_path), "BTCUSDT", "1h", self._trade())
        content = (tmp_path / "BTCUSDT-1h-trades_backtest.csv").read_text()
        assert "symbol" in content
        assert "side" in content

    def test_should_append_rows_without_duplicate_header(self, tmp_path):
        guardar_trade_a_csv(str(tmp_path), "BTCUSDT", "1h", self._trade("BUY"))
        guardar_trade_a_csv(str(tmp_path), "BTCUSDT", "1h", self._trade("SELL"))
        content = (tmp_path / "BTCUSDT-1h-trades_backtest.csv").read_text()
        lines = [ln for ln in content.strip().split("\n") if ln]
        # 1 header + 2 trades
        assert len(lines) == 3

    def test_should_write_trade_side_in_row(self, tmp_path):
        guardar_trade_a_csv(str(tmp_path), "BTCUSDT", "1h", self._trade("SELL"))
        content = (tmp_path / "BTCUSDT-1h-trades_backtest.csv").read_text()
        assert "SELL" in content


# ─── Tests: run_backtest ──────────────────────────────────────────────────────

class TestRunBacktest:

    def test_should_return_zero_trades_for_empty_dataframe(self):
        # Given
        strategy = _NeverTradeStrategy()
        # When
        trade_count, stats = run_backtest(strategy, pd.DataFrame(), "BTCUSDT")
        # Then
        assert trade_count == 0
        assert stats["symbol"] == "BTCUSDT"

    def test_should_return_zero_trades_when_strategy_never_signals(self):
        # Given
        strategy = _NeverTradeStrategy()
        df = _df_mock(50)
        # When
        trade_count, _stats = run_backtest(strategy, df, "ETHUSDT")
        # Then
        assert trade_count == 0

    def test_should_return_stats_dict_with_all_required_keys(self):
        strategy = _NeverTradeStrategy()
        _, stats = run_backtest(strategy, _df_mock(20), "BTCUSDT")
        required_keys = {
            "symbol", "timeframe", "op_ganadas", "op_perdidas",
            "op_totales", "max_drawdown", "retorno_acumulado",
            "win_rate", "profit_factor", "resultado",
        }
        assert required_keys.issubset(set(stats.keys()))

    def test_should_record_resultado_neutro_when_no_trades(self):
        strategy = _NeverTradeStrategy()
        _, stats = run_backtest(strategy, _df_mock(20), "BTCUSDT")
        assert stats["resultado"] == "NEUTRO"

    def test_should_record_at_least_two_trade_events_on_buy_and_sell(self):
        # Given: estrategia que compra en la primera vela y vende en la segunda
        strategy = _BuyOnceStrategy()
        df = _df_mock(10)
        # When
        trade_count, _ = run_backtest(strategy, df, "BTCUSDT")
        # Then: 1 BUY + 1 SELL = 2 eventos
        assert trade_count >= 2

    def test_should_write_csv_file_when_escribir_trades_is_true(self, tmp_path, monkeypatch):
        import backtest_engine
        monkeypatch.setattr(backtest_engine, "results_root", str(tmp_path))
        strategy = _BuyOnceStrategy()
        carpeta = crear_carpeta_estrategia("TestEscribir")
        run_backtest(strategy, _df_mock(10), "BTCUSDT",
                     carpeta_estrategia=carpeta, timeframe="1h", escribir_trades=True)
        csv_files = list(tmp_path.glob("**/*.csv"))
        assert len(csv_files) >= 1


# ─── Tests: run_backtest_with_predictions ────────────────────────────────────

class TestRunBacktestWithPredictions:

    def test_should_return_empty_stats_for_empty_dataframe(self):
        # Given
        strategy = _NeverTradeStrategy()
        # When
        result = run_backtest_with_predictions(strategy, pd.DataFrame(), np.array([]), "BTCUSDT")
        # Then
        assert result["symbol"] == "BTCUSDT"
        assert "sharpe" in result

    def test_should_include_sharpe_in_result(self):
        strategy = _NeverTradeStrategy()
        df = _df_mock(20)
        result = run_backtest_with_predictions(strategy, df, np.zeros(20, dtype=int), "BTCUSDT")
        assert "sharpe" in result

    def test_should_handle_predictions_shorter_than_dataframe(self):
        # Given
        strategy = _NeverTradeStrategy()
        df = _df_mock(20)
        predictions = np.ones(10, dtype=int)  # más corto que df
        # When / Then: no debe lanzar
        result = run_backtest_with_predictions(strategy, df, predictions, "BTCUSDT")
        assert result["symbol"] == "BTCUSDT"

    def test_should_generate_trades_with_alternating_buy_hold_signals(self):
        # Given: señales que entran y salen
        strategy = _NeverTradeStrategy()
        df = _df_mock(20)
        # [BUY, BUY, HOLD, HOLD, BUY, ...] → abre y cierra posiciones
        predictions = np.array([1, 1, 0, 0, 1, 1, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0], dtype=int)
        # When
        result = run_backtest_with_predictions(strategy, df, predictions, "BTCUSDT")
        # Then
        assert result["op_totales"] > 0

    def test_should_close_open_position_at_end_of_series(self):
        # Given: predice compra en todo el rango → posición queda abierta hasta el final
        strategy = _NeverTradeStrategy()
        df = _df_mock(10)
        predictions = np.ones(10, dtype=int)
        # When
        result = run_backtest_with_predictions(strategy, df, predictions, "BTCUSDT")
        # Then: al cierre forzado al final, op_totales >= 1
        assert result["op_totales"] >= 1


# ─── Tests: _generate_empty_stats ────────────────────────────────────────────

class TestGenerateEmptyStats:

    def test_should_return_dict_with_correct_symbol(self):
        strategy = _NeverTradeStrategy()
        stats = _generate_empty_stats("SOLUSDT", strategy)
        assert stats["symbol"] == "SOLUSDT"

    def test_should_have_zero_for_all_trade_counters(self):
        strategy = _NeverTradeStrategy()
        stats = _generate_empty_stats("BTCUSDT", strategy)
        assert stats["op_totales"] == 0
        assert stats["op_ganadas"] == 0
        assert stats["op_perdidas"] == 0

    def test_should_have_resultado_neutro(self):
        strategy = _NeverTradeStrategy()
        stats = _generate_empty_stats("BTCUSDT", strategy)
        assert stats["resultado"] == "NEUTRO"

    def test_should_include_timeframe_from_strategy(self):
        strategy = _NeverTradeStrategy()
        stats = _generate_empty_stats("BTCUSDT", strategy)
        assert stats["timeframe"] == "4h"


# ─── Tests: _calculate_backtest_stats ────────────────────────────────────────

class TestCalculateBacktestStats:

    def _call(self, final_capital: float, initial_capital: float = 10_000.0,
              op_ganadas: int = 3, op_perdidas: int = 2,
              trade_count: int = 10, pos_pnl: float = 300.0, neg_pnl: float = 100.0):
        strategy = _NeverTradeStrategy()
        strategy.capital = initial_capital
        df = _df_mock(20)
        return _calculate_backtest_stats(
            strategy, final_capital, [initial_capital, final_capital],
            200.0, df, "BTCUSDT", trade_count, op_ganadas, op_perdidas, pos_pnl, neg_pnl,
        )

    def test_should_set_resultado_ganancia_when_positive_return(self):
        stats = self._call(final_capital=11_000.0)
        assert stats["resultado"] == "GANANCIA"

    def test_should_set_resultado_perdida_when_negative_return(self):
        stats = self._call(final_capital=9_000.0)
        assert stats["resultado"] == "PERDIDA"

    def test_should_calculate_win_rate_correctly(self):
        # op_ganadas=3, op_totales=5 (trade_count//2) → 60%
        stats = self._call(final_capital=10_200.0, trade_count=10, op_ganadas=3, op_perdidas=2)
        assert abs(stats["win_rate"] - 60.0) < 0.001

    def test_should_calculate_profit_factor_correctly(self):
        # pos_pnl=300, neg_pnl=100 → PF=3.0
        stats = self._call(final_capital=10_200.0, pos_pnl=300.0, neg_pnl=100.0)
        assert abs(stats["profit_factor"] - 3.0) < 0.001

    def test_should_include_fecha_inicio_and_fecha_fin(self):
        stats = self._call(final_capital=10_000.0)
        assert stats["fecha_inicio"] != "N/A"
        assert stats["fecha_fin"] != "N/A"


# ─── Costes, bucle único y métricas (#55 #67 #73 #78) ────────────────────────

def _flat_df(closes: list[float]) -> pd.DataFrame:
    n = len(closes)
    return pd.DataFrame({
        "timestamp": np.arange(n, dtype=np.int64) * 3_600_000 + 1_700_000_000_000,
        "open": closes, "high": closes, "low": closes, "close": closes, "volume": [1.0] * n,
    })


class _ScriptedStrategy(_NeverTradeStrategy):
    """Compra en la vela `buy_at`, nunca vende por señal; should_close configurable."""
    capital = 10_000.0
    risk_per_trade = 0.5

    def __init__(self, buy_at: int = 0, close_below: float | None = None):
        self._buy_at = buy_at
        self._close_below = close_below
        self.seen_columns: set[str] = set()

    def should_buy(self, row: dict) -> bool:
        self.seen_columns |= set(row)
        return row["close"] == self._buy_at

    def should_close(self, row: dict, entry_price: float) -> bool:
        return self._close_below is not None and row["close"] < self._close_below


def test_los_costes_restan_rendimiento_en_un_precio_plano():
    df = _flat_df([100.0] * 5)
    preds = np.array([1, 0, 0, 0, 0])

    sin_costes = run_backtest_with_predictions(_NeverTradeStrategy(), df, preds, "X", fee_rate=0, slippage=0)
    con_costes = run_backtest_with_predictions(_NeverTradeStrategy(), df, preds, "X")

    assert sin_costes["retorno_total"] == 0
    assert con_costes["retorno_total"] < 0
    assert con_costes["resultado"] == "PERDIDA"


def test_clasico_cierra_la_posicion_final_y_cuenta_la_operacion():
    df = _flat_df([100.0, 101.0, 102.0])

    trade_count, stats = run_backtest(_ScriptedStrategy(buy_at=100), df, "X", fee_rate=0, slippage=0)

    assert trade_count == 2 and stats["op_totales"] == 1  # BUY + SELL forzado al final
    assert stats["op_ganadas"] == 1


def test_clasico_aplica_should_close_como_stop():
    df = _flat_df([100.0, 90.0, 120.0])  # el stop salta en 90, antes de la recuperación

    _, stats = run_backtest(_ScriptedStrategy(buy_at=100, close_below=95), df, "X", fee_rate=0, slippage=0)

    assert stats["op_perdidas"] == 1 and stats["retorno_total"] < 0


def test_el_drawdown_incluye_la_perdida_no_realizada():
    # sube, cae con la posición abierta y se recupera: el trade cierra en positivo
    df = _flat_df([100.0, 120.0, 96.0, 130.0])
    preds = np.array([1, 1, 1, 0])

    stats = run_backtest_with_predictions(_NeverTradeStrategy(), df, preds, "X", fee_rate=0, slippage=0)

    assert stats["retorno_total"] > 0
    assert stats["max_drawdown"] > 0
    # tamaño 1 (arriesga 5000 * 1 % = 50 con el stop a 50 de distancia): pico 5020 en el 120, valle 4996 en el 96
    assert stats["max_drawdown"] == pytest.approx(24.0)
    assert stats["max_drawdown_pct"] == pytest.approx(24 / 5020 * 100)


def test_profit_factor_sin_perdidas_usa_el_tope_y_no_la_ganancia_absoluta():
    df = _flat_df([100.0, 200.0])
    stats = run_backtest_with_predictions(_NeverTradeStrategy(), df, np.array([1, 0]), "X", fee_rate=0, slippage=0)

    assert stats["profit_factor"] == 999.0


def test_abs_drawdown_es_capital_inicial_menos_el_minimo():
    df = _flat_df([100.0, 50.0, 100.0])
    stats = run_backtest_with_predictions(_NeverTradeStrategy(), df, np.array([1, 1, 0]), "X", fee_rate=0, slippage=0)

    # salta el stop (50) y se pierde exactamente lo arriesgado: 1 % de 5000
    assert stats["abs_drawdown"] == pytest.approx(50.0)


def test_las_columnas_con_nombre_no_valido_llegan_intactas_a_la_estrategia():
    df = _flat_df([100.0, 101.0])
    df["BBL_20_2.0"] = 1.0
    strategy = _ScriptedStrategy(buy_at=100)

    run_backtest(strategy, df, "X")

    assert "BBL_20_2.0" in strategy.seen_columns


def test_sharpe_es_cero_sin_variacion_y_finito_con_ella():
    plano = run_backtest_with_predictions(
        _NeverTradeStrategy(), _flat_df([100.0] * 6), np.zeros(6, dtype=int), "X")
    movido = run_backtest_with_predictions(
        _NeverTradeStrategy(), _flat_df([100, 110, 99, 120, 100, 130]), np.array([1, 1, 1, 1, 1, 0]), "X")

    assert plano["sharpe"] == 0.0
    assert np.isfinite(movido["sharpe"]) and movido["sharpe"] != 0.0


# ─── Ejecución con órdenes límite ─────────────────────────────────────────────

def _ohlc_df(rows: list[tuple[float, float, float, float]]) -> pd.DataFrame:
    """Velas (open, high, low, close) cada hora."""
    o, h, low, c = zip(*rows, strict=True)
    return pd.DataFrame({
        "timestamp": np.arange(len(rows), dtype=np.int64) * 3_600_000 + 1_700_000_000_000,
        "open": o, "high": h, "low": low, "close": c, "volume": [1.0] * len(rows),
    })


def test_la_compra_limite_no_se_llena_si_el_precio_no_la_atraviesa():
    # señal en la vela 0 (close 100); la 1 solo toca 100, no lo cruza; la 2 ya no tiene orden viva
    df = _ohlc_df([(100, 100, 100, 100), (101, 102, 100, 101), (101, 102, 100, 102)])

    n, stats = run_backtest(_ScriptedStrategy(buy_at=100), df, "X", fee_rate=0, slippage=0, execution="limit")

    assert n == 0 and stats["op_totales"] == 0


def test_la_compra_limite_se_llena_a_su_precio_sin_slippage():
    df = _ohlc_df([(100, 100, 100, 100), (100, 101, 99, 100), (100, 111, 100, 110)])
    # tope del 25 % del equity (2500): market entra a 101 y limit a 100; los dos salen a 110 - 1 %
    mercado = run_backtest(_ScriptedStrategy(buy_at=100), df, "X", fee_rate=0, slippage=0.01)[1]
    limite = run_backtest(_ScriptedStrategy(buy_at=100), df, "X", fee_rate=0, slippage=0.01, execution="limit")[1]

    assert mercado["retorno_total"] == pytest.approx(2500 / 101 * (108.9 - 101))
    assert limite["retorno_total"] == pytest.approx(25 * (108.9 - 100))


def test_un_hueco_a_la_baja_llena_la_compra_limite_a_la_apertura():
    df = _ohlc_df([(100, 100, 100, 100), (95, 96, 94, 95), (95, 106, 95, 105)])
    stats = run_backtest(_ScriptedStrategy(buy_at=100), df, "X", fee_rate=0, slippage=0, execution="limit")[1]

    assert stats["retorno_total"] == pytest.approx(2500 / 95 * (105 - 95))  # entró a 95, no a 100


def test_la_vela_del_relleno_no_evalua_la_salida_por_senal():
    df = _ohlc_df([(100, 100, 100, 100), (100, 101, 99, 99), (99, 99, 99, 99)])
    estrategia = _ScriptedStrategy(buy_at=100, close_below=100)  # cierra en cuanto el close baja de 100

    n, _ = run_backtest(estrategia, df, "X", fee_rate=0, slippage=0, execution="limit")

    assert n == 2  # BUY en la vela 1 y SELL en la 2 (no en la del relleno)


def test_la_venta_limite_caduca_si_el_precio_no_sube_y_sale_en_la_siguiente():
    df = _ohlc_df([
        (100, 100, 100, 100),   # señal de compra
        (100, 101, 99, 100),    # se llena a 100
        (100, 100, 98, 98),     # should_close: orden de venta a 98
        (98, 98, 97, 97),       # no sube de 98: la orden caduca; la estrategia vuelve a querer salir (97)
        (97, 99, 97, 98),       # el máximo (99) supera 97: se vende a 97
    ])
    estrategia = _ScriptedStrategy(buy_at=100, close_below=99)

    n, stats = run_backtest(estrategia, df, "X", fee_rate=0, slippage=0, execution="limit")

    assert n == 2 and stats["retorno_total"] == pytest.approx(25 * (97 - 100))


def test_el_modo_de_ejecucion_invalido_se_rechaza():
    with pytest.raises(ValueError, match="execution"):
        run_backtest(_ScriptedStrategy(), _flat_df([100.0, 101.0]), "X", execution="iceberg")


def test_si_la_vela_del_relleno_toca_el_stop_se_asume_que_salta():
    # se llena a 100 (mínimo 40 < 100) y la misma vela baja del stop (50): pesimista, salta el stop
    df = _ohlc_df([(100, 100, 100, 100), (100, 101, 40, 90), (90, 90, 90, 90)])

    n, stats = run_backtest(_ScriptedStrategy(buy_at=100), df, "X", fee_rate=0, slippage=0, execution="limit")

    assert n == 2 and stats["op_perdidas"] == 1
    assert stats["retorno_total"] == pytest.approx(25 * (50 - 100))
