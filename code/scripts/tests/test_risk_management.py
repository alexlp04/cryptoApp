"""test_risk_management.py — Dimensionado por riesgo y salidas protectoras del backtest."""
from __future__ import annotations

import numpy as np
import pandas as pd
import pytest
from backtest_engine import _protective_exit, run_backtest
from risk_management import MAX_POSITION_FRACTION, position_size, risk_amount, valid_stop


def test_el_tamano_arriesga_exactamente_la_fraccion_del_equity():
    units = position_size(equity=10_000, risk_fraction=0.01, entry_price=100.0, stop_price=95.0)

    assert risk_amount(units, 100.0, 95.0) == pytest.approx(100.0)  # 1 % de 10 000


def test_un_stop_mas_ajustado_permite_mas_unidades_hasta_el_tope_por_posicion():
    ancho = position_size(10_000, 0.01, 100.0, 90.0)
    estrecho = position_size(10_000, 0.01, 100.0, 99.0)

    assert estrecho > ancho
    assert estrecho * 100.0 <= 10_000 * MAX_POSITION_FRACTION + 1e-9  # el tope manda


def test_sin_stop_valido_no_se_dimensiona():
    assert position_size(10_000, 0.01, 100.0, 100.0) == 0.0
    assert position_size(10_000, 0.01, 100.0, 120.0) == 0.0
    assert position_size(10_000, 0.01, 100.0, 0.0) == 0.0
    assert position_size(0, 0.01, 100.0, 95.0) == 0.0
    assert not valid_stop(100.0, None)


def test_el_stop_salta_dentro_de_la_vela_aunque_el_cierre_se_recupere():
    # abre en 100 (stop 95); la vela 2 baja a 90 y cierra en 100
    assert _protective_exit(100.0, 101.0, 90.0, 95.0, 110.0) == (95.0, True)


def test_un_hueco_bajista_ejecuta_el_stop_a_la_apertura():
    assert _protective_exit(90.0, 92.0, 88.0, 95.0, 110.0) == (90.0, True)


def test_el_objetivo_es_una_orden_limite_sin_slippage_y_un_hueco_al_alza_mejora_el_precio():
    assert _protective_exit(100.0, 112.0, 99.0, 95.0, 110.0) == (110.0, False)
    assert _protective_exit(115.0, 118.0, 114.0, 95.0, 110.0) == (115.0, False)


def test_si_una_vela_toca_stop_y_objetivo_gana_el_stop():
    assert _protective_exit(100.0, 120.0, 90.0, 95.0, 110.0) == (95.0, True)


class _Compra:
    capital = 10_000.0
    risk_per_trade = 0.01

    def __init__(self, stop_pct=0.05, tp_pct=0.10):
        self._stop, self._tp = stop_pct, tp_pct

    def populate_indicators(self, df):
        return df

    def should_buy(self, row):
        return row["timestamp"] == 0

    def should_sell(self, row):
        return False

    def should_close(self, row, entry_price):
        return False

    def get_stop_loss(self, entry_price, row):
        return entry_price * (1 - self._stop)

    def get_take_profit(self, entry_price, row):
        return entry_price * (1 + self._tp)


def _velas(filas):
    return pd.DataFrame([
        {"timestamp": i, "open": o, "high": h, "low": low, "close": c, "volume": 1.0}
        for i, (o, h, low, c) in enumerate(filas)
    ])


def test_una_operacion_perdedora_pierde_la_fraccion_de_riesgo_y_no_mas():
    # entra a 100 (stop 95); la segunda vela perfora el stop y cierra recuperada
    df = _velas([(100, 100, 100, 100), (100, 101, 90, 100), (100, 100, 100, 100)])

    _, stats = run_backtest(_Compra(), df, "X", fee_rate=0, slippage=0)

    assert stats["retorno_total"] == pytest.approx(-100.0)  # 1 % de 10 000
    assert stats["op_perdidas"] == 1


def test_una_operacion_ganadora_en_el_objetivo_gana_dos_veces_lo_arriesgado():
    # stop 5 %, objetivo 10 % → relación 2:1
    df = _velas([(100, 100, 100, 100), (100, 111, 99, 105), (105, 105, 105, 105)])

    _, stats = run_backtest(_Compra(), df, "X", fee_rate=0, slippage=0)

    assert stats["retorno_total"] == pytest.approx(200.0)
    assert stats["op_ganadas"] == 1


def test_una_estrategia_sin_stop_valido_no_opera():
    df = _velas([(100, 100, 100, 100), (100, 100, 100, 100)])

    trades, stats = run_backtest(_Compra(stop_pct=-0.1), df, "X", fee_rate=0, slippage=0)

    assert trades == 0 and stats["retorno_total"] == 0


def test_el_tope_por_posicion_limita_la_perdida_cuando_el_stop_es_muy_estrecho():
    # stop 0,1 %: por riesgo serían 100 000 € de posición; el tope del 25 % deja 2 500
    df = _velas([(100, 100, 100, 100), (100, 100, 99, 99.9), (100, 100, 100, 100)])

    _, stats = run_backtest(_Compra(stop_pct=0.001), df, "X", fee_rate=0, slippage=0)

    assert abs(stats["retorno_total"]) <= 2_500 * 0.001 + 1e-6
    assert np.isfinite(stats["retorno_total"])
