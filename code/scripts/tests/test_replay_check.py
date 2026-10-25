"""test_replay_check.py — El replay compara el motor RT con el backtest sobre las mismas velas."""
from __future__ import annotations

import numpy as np
import pandas as pd
from replay_check import format_report, replay_vs_backtest

HOUR = 3_600_000


class _Ciclo:
    """Compra en las velas 0, 10, 20... y vende en las 5, 15, 25...; el stop depende de `stop_pct`."""
    WARMUP_PERIOD = 20
    capital = 10_000.0
    risk_per_trade = 0.01
    stop_pct = 0.5

    def populate_indicators(self, df: pd.DataFrame) -> pd.DataFrame:
        return df

    def should_buy(self, row: dict) -> bool:
        return int(row["timestamp"]) // HOUR % 10 == 0

    def should_sell(self, row: dict) -> bool:
        return int(row["timestamp"]) // HOUR % 10 == 5

    def should_close(self, row: dict, entry_price: float) -> bool:
        return False

    def get_stop_loss(self, entry_price: float, row: dict) -> float:
        return entry_price * self.stop_pct

    def get_take_profit(self, entry_price: float, row: dict) -> float:
        return entry_price * 10


def _velas(n: int = 120) -> pd.DataFrame:
    close = 100 + np.cumsum(np.random.default_rng(3).normal(0, 0.1, n))
    return pd.DataFrame({"timestamp": np.arange(n, dtype=np.int64) * HOUR, "open": close, "high": close + 0.05,
                         "low": close - 0.05, "close": close, "volume": 1.0})


def test_una_estrategia_sin_dependencia_de_la_ventana_da_las_mismas_operaciones():
    r = replay_vs_backtest(_Ciclo, _velas(), window=30, candles=60)

    assert r["backtest"] > 4 and r["matched"] == r["backtest"] == r["rt"]
    assert not r["only_backtest"] and not r["only_rt"]


def test_el_stop_salta_en_la_misma_vela_en_el_backtest_y_en_el_motor():
    class ConStop(_Ciclo):
        stop_pct = 0.99

    df = _velas()
    df.loc[73, ["low", "close"]] = [97.0, 98.0]  # la vela 53 atraviesa el stop de la compra de la 70

    r = replay_vs_backtest(ConStop, df, window=30, candles=60)

    assert r["matched"] == r["backtest"] == r["rt"]
    assert ("SELL", 73 * HOUR) in r["matched_keys"]  # el stop cierra en la 73, no en la 75 de la señal
    assert r["max_price_dev"] < 0.01


def test_el_informe_resume_lo_que_difiere():
    r = replay_vs_backtest(_Ciclo, _velas(), window=30, candles=60)

    assert "coinciden" in format_report(r)
