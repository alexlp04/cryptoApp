"""test_consistency_check.py — La herramienta detecta lookahead y dependencia del buffer."""
from __future__ import annotations

import numpy as np
import pandas as pd
import pytest
from consistency_check import check_consistency, format_report


class _Estrategia:
    """Indicador configurable sobre el close; compra cuando es mayor que el close."""
    allow_short = False

    def __init__(self, indicador):
        self._indicador = indicador

    def populate_indicators(self, df: pd.DataFrame) -> pd.DataFrame:
        df["ind"] = self._indicador(df["close"])
        return df

    def should_buy(self, row: dict) -> bool:
        return row["ind"] > row["close"]

    def should_sell(self, row: dict) -> bool:
        return False

    def get_stop_loss(self, entry_price: float, row: dict) -> float:
        return entry_price * 0.95

    def get_take_profit(self, entry_price: float, row: dict) -> float:
        return entry_price * 1.1


@pytest.fixture
def velas() -> pd.DataFrame:
    close = 100 + np.cumsum(np.random.default_rng(1).normal(0, 1, 400))
    return pd.DataFrame({"timestamp": np.arange(400), "open": close, "high": close + 1, "low": close - 1,
                         "close": close, "volume": 1.0})


def test_un_indicador_con_ventana_corta_es_consistente(velas):
    r = check_consistency(_Estrategia(lambda c: c.rolling(5).mean()), velas, window=50, samples=40)

    assert not r["lookahead"]["columns"] and not r["buffer"]["columns"]
    assert not r["lookahead"]["signals"] and not r["buffer"]["signals"]


def test_usar_el_futuro_se_detecta_como_lookahead(velas):
    r = check_consistency(_Estrategia(lambda c: c.shift(-1)), velas, window=50, samples=40)

    assert "ind" in r["lookahead"]["columns"]
    assert "should_buy" in r["lookahead"]["signals"]
    assert "LOOKAHEAD" in format_report(r) and "HAY DIFERENCIAS" in format_report(r)


def test_una_media_exponencial_depende_de_la_historia_que_ve_el_buffer(velas):
    r = check_consistency(_Estrategia(lambda c: c.ewm(span=20, adjust=False).mean()), velas, window=30, samples=40)

    assert "ind" in r["buffer"]["columns"]
    assert not r["lookahead"]["columns"]  # el prefijo sí ve la misma historia


def test_un_indicador_que_necesita_mas_velas_que_el_buffer_sale_nan_en_vivo(velas):
    r = check_consistency(_Estrategia(lambda c: c.rolling(80).mean()), velas, window=50, samples=40)

    assert r["buffer"]["columns"]["ind"]["max_rel"] == float("inf")


def test_hacen_falta_mas_velas_que_la_ventana(velas):
    with pytest.raises(ValueError, match="más de"):
        check_consistency(_Estrategia(lambda c: c), velas.head(30), window=50)
