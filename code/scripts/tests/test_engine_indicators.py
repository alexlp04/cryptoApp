"""test_engine_indicators.py — Tests unitarios del motor de indicadores técnicos."""
from __future__ import annotations

import numpy as np
import pandas as pd
import pytest
from engine_indicators import calcular_indicadores, compute_basic_indicators

# ─── Helpers ─────────────────────────────────────────────────────────────────

def _df_close(n: int = 100, with_id: bool = True) -> pd.DataFrame:
    """DataFrame mínimo con columnas OHLC e 'id' para tests de indicadores."""
    rng = np.random.default_rng(seed=99)
    close = 30_000.0 + np.cumsum(rng.normal(0, 100, n))
    df = pd.DataFrame({
        "close": close,
        "high":  close * 1.002,
        "low":   close * 0.998,
    })
    if with_id:
        df["id"] = np.arange(1, n + 1, dtype=np.int64)
    return df


# ─── Tests: compute_basic_indicators ─────────────────────────────────────────

class TestComputeBasicIndicators:

    def test_should_add_sma_14_column(self):
        df = compute_basic_indicators(_df_close(50))
        assert "SMA_14" in df.columns

    def test_should_add_ema_14_column(self):
        df = compute_basic_indicators(_df_close(50))
        assert "EMA_14" in df.columns

    def test_should_add_rsi_14_column(self):
        df = compute_basic_indicators(_df_close(50))
        assert "RSI_14" in df.columns

    def test_should_add_macd_column(self):
        df = compute_basic_indicators(_df_close(50))
        assert "MACD" in df.columns

    def test_should_add_macd_signal_column(self):
        df = compute_basic_indicators(_df_close(50))
        assert "MACD_signal" in df.columns

    def test_should_preserve_original_row_count(self):
        df = _df_close(80)
        result = compute_basic_indicators(df)
        assert len(result) == 80

    def test_should_not_modify_the_input_dataframe(self):
        df = _df_close(50)
        cols_before = set(df.columns)
        compute_basic_indicators(df)
        assert set(df.columns) == cols_before

    def test_should_accept_string_typed_close_column(self):
        # Java envía precios como strings → debe convertirlos a numérico
        df = _df_close(50)
        df["close"] = df["close"].astype(str)
        result = compute_basic_indicators(df)
        assert "RSI_14" in result.columns

    def test_rsi_values_should_be_between_0_and_100_when_not_nan(self):
        df = compute_basic_indicators(_df_close(100))
        valid_rsi = df["RSI_14"].dropna()
        assert (valid_rsi >= 0).all()
        assert (valid_rsi <= 100).all()

    def test_sma_14_should_be_nan_for_first_13_rows(self):
        # SMA con ventana 14: las primeras 13 filas son NaN
        df = compute_basic_indicators(_df_close(50))
        assert df["SMA_14"].iloc[:13].isna().all()

    def test_sma_14_should_not_be_nan_from_row_14_onwards(self):
        df = compute_basic_indicators(_df_close(50))
        assert not df["SMA_14"].iloc[13:].isna().any()

    def test_ema_14_should_have_valid_values_after_warmup(self):
        df = compute_basic_indicators(_df_close(50))
        # EMA de la librería 'ta' calcula desde la primera fila
        assert not df["EMA_14"].dropna().empty


# ─── Tests: calcular_indicadores ─────────────────────────────────────────────

class TestCalcularIndicadores:

    def test_should_return_a_list(self):
        result = calcular_indicadores(_df_close(60))
        assert isinstance(result, list)

    def test_should_return_list_of_dicts(self):
        result = calcular_indicadores(_df_close(60))
        assert all(isinstance(r, dict) for r in result)

    def test_should_raise_value_error_when_id_column_is_missing(self):
        df = _df_close(50, with_id=False)
        with pytest.raises(ValueError, match="'id'"):
            calcular_indicadores(df)

    def test_each_record_should_contain_required_keys(self):
        result = calcular_indicadores(_df_close(50))
        required = {"id", "tipo", "parametros", "valor"}
        for record in result[:20]:
            assert required.issubset(record.keys())

    def test_should_not_include_records_with_nan_valor(self):
        result = calcular_indicadores(_df_close(50))
        for record in result:
            assert not np.isnan(record["valor"])

    def test_should_produce_all_5_indicator_types(self):
        result = calcular_indicadores(_df_close(50))
        tipos = {r["tipo"] for r in result}
        assert tipos == {"SMA_14", "EMA_14", "RSI_14", "MACD", "MACD_signal"}

    def test_id_field_should_be_integer(self):
        result = calcular_indicadores(_df_close(50))
        for r in result[:10]:
            assert isinstance(r["id"], int)

    def test_valor_field_should_be_float(self):
        result = calcular_indicadores(_df_close(50))
        for r in result[:10]:
            assert isinstance(r["valor"], float)

    def test_parametros_field_should_be_string(self):
        result = calcular_indicadores(_df_close(50))
        for r in result[:10]:
            assert isinstance(r["parametros"], str)

    def test_should_produce_multiple_records_per_indicator_type(self):
        result = calcular_indicadores(_df_close(60))
        rsi_records = [r for r in result if r["tipo"] == "RSI_14"]
        # Hay 60 filas; después de dropna algunas son NaN → debe quedar un número significativo
        assert len(rsi_records) > 40

    def test_output_size_should_grow_with_more_input_rows(self):
        result_small = calcular_indicadores(_df_close(40))
        result_large = calcular_indicadores(_df_close(80))
        assert len(result_large) > len(result_small)
