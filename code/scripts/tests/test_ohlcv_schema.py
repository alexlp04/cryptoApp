"""Contrato de esquema OHLCV y detección de huecos temporales."""
from __future__ import annotations

import numpy as np
import pandas as pd
import pytest
from hypothesis import given, settings
from hypothesis import strategies as st
from pandera.errors import SchemaError, SchemaErrors

from ohlcv_schema import find_time_gaps, validate_ohlcv

HOUR_MS = 3_600_000


def _velas(n: int = 50, start: int = 1_700_000_000_000, step: int = HOUR_MS) -> pd.DataFrame:
    """Velas sintéticas coherentes: high es el máximo real y low el mínimo real."""
    rng = np.random.default_rng(seed=7)
    close = 40_000.0 + np.cumsum(rng.normal(0, 50, n))
    open_ = close * 0.999
    return pd.DataFrame({
        "timestamp": np.arange(start, start + n * step, step, dtype=np.int64),
        "open": open_,
        "high": np.maximum(open_, close) * 1.001,
        "low": np.minimum(open_, close) * 0.999,
        "close": close,
        "volume": rng.uniform(100, 1_000, n),
    })


class TestContratoValido:

    def test_acepta_velas_coherentes(self):
        df = _velas()
        assert len(validate_ohlcv(df)) == len(df)

    def test_permite_columnas_de_indicadores_extra(self):
        # Los engines enriquecen el DataFrame; eso no debe romper el contrato.
        df = _velas()
        df["rsi_fast"] = 55.0
        df["ema_gap"] = 0.001

        assert "rsi_fast" in validate_ohlcv(df).columns

    def test_volumen_cero_es_valido(self):
        # Una vela sin operaciones es legítima, no un dato corrupto.
        df = _velas()
        df.loc[3, "volume"] = 0.0

        validate_ohlcv(df)


class TestDatosCorruptos:

    def test_rechaza_nan_en_close(self):
        df = _velas()
        df.loc[10, "close"] = np.nan

        with pytest.raises((SchemaError, SchemaErrors)):
            validate_ohlcv(df)

    def test_rechaza_high_menor_que_low(self):
        df = _velas()
        df.loc[5, "high"] = df.loc[5, "low"] - 1.0

        with pytest.raises((SchemaError, SchemaErrors)):
            validate_ohlcv(df)

    def test_rechaza_low_por_encima_del_cierre(self):
        df = _velas()
        df.loc[8, "low"] = df.loc[8, "close"] + 10.0

        with pytest.raises((SchemaError, SchemaErrors)):
            validate_ohlcv(df)

    def test_rechaza_precio_negativo(self):
        df = _velas()
        df.loc[2, "open"] = -1.0

        with pytest.raises((SchemaError, SchemaErrors)):
            validate_ohlcv(df)

    def test_rechaza_timestamps_duplicados(self):
        # Un duplicado suele venir de un fetch incremental que se solapa.
        df = _velas()
        df.loc[4, "timestamp"] = df.loc[3, "timestamp"]

        with pytest.raises((SchemaError, SchemaErrors)):
            validate_ohlcv(df)

    def test_rechaza_volumen_negativo(self):
        df = _velas()
        df.loc[6, "volume"] = -0.5

        with pytest.raises((SchemaError, SchemaErrors)):
            validate_ohlcv(df)


class TestHuecosTemporales:

    def test_serie_continua_no_tiene_huecos(self):
        assert find_time_gaps(_velas(30), HOUR_MS) == []

    def test_detecta_un_hueco(self):
        df = _velas(20)
        df = df.drop(index=[10, 11]).reset_index(drop=True)

        huecos = find_time_gaps(df, HOUR_MS)

        assert len(huecos) == 1
        anterior, siguiente = huecos[0]
        assert siguiente - anterior == 3 * HOUR_MS

    def test_no_le_afecta_el_orden_de_las_filas(self):
        df = _velas(20).sample(frac=1.0, random_state=3).reset_index(drop=True)

        assert find_time_gaps(df, HOUR_MS) == []

    def test_step_invalido_es_error_explicito(self):
        with pytest.raises(ValueError, match="positivo"):
            find_time_gaps(_velas(5), 0)

    @pytest.mark.parametrize("n", [0, 1])
    def test_series_demasiado_cortas_no_tienen_huecos(self, n):
        assert find_time_gaps(_velas(max(n, 1)).head(n), HOUR_MS) == []


class TestPropiedadesHuecos:
    """Propiedades que deben cumplirse para cualquier serie, no solo las de ejemplo."""

    @settings(max_examples=50, deadline=None)
    @given(
        n=st.integers(min_value=2, max_value=60),
        drop=st.integers(min_value=0, max_value=5),
    )
    def test_quitar_velas_del_centro_nunca_reduce_los_huecos(self, n, drop):
        df = _velas(n)
        drop = min(drop, max(n - 2, 0))

        completa = find_time_gaps(df, HOUR_MS)
        assert completa == []

        if drop:
            recortada = df.drop(index=range(1, 1 + drop)).reset_index(drop=True)
            # Quitar velas interiores siempre abre exactamente un hueco.
            assert len(find_time_gaps(recortada, HOUR_MS)) == 1

    @settings(max_examples=50, deadline=None)
    @given(step=st.sampled_from([60_000, 300_000, HOUR_MS, 86_400_000]))
    def test_serie_generada_con_su_propio_step_nunca_tiene_huecos(self, step):
        df = _velas(25, step=step)

        assert find_time_gaps(df, step) == []
