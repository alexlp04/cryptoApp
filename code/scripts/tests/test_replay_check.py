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


# ── Motor RT con IA ──────────────────────────────────────────────────────────

class _EstrategiaIA:
    """Una sola feature (el momento a 5 velas); stop lejano para que mande la predicción."""
    WARMUP_PERIOD = 20
    capital = 10_000.0
    risk_per_trade = 0.01

    def populate_indicators(self, df: pd.DataFrame) -> pd.DataFrame:
        df["mom"] = df["close"].pct_change(5)
        return df

    def get_warmup_period(self) -> int:
        return 20

    def get_name(self) -> str:
        return "EstrategiaIA"

    def get_feature_columns(self, df: pd.DataFrame) -> list[str]:
        return ["mom"]

    def should_close(self, row: dict, entry_price: float) -> bool:
        return False

    def get_stop_loss(self, entry_price: float, row: dict) -> float:
        return entry_price * 0.5

    def get_take_profit(self, entry_price: float, row: dict) -> float:
        return entry_price * 10


class _ModeloMomento:
    """3 clases: sube si el momento supera +0,1 %, baja si cae de -0,1 %, neutral en medio."""
    classes_ = (-1, 0, 1)
    feature_cols = ("mom",)

    def predict(self, x):
        mom = np.nan_to_num(x[:, 0])
        return np.where(mom > 0.001, 1, np.where(mom < -0.001, -1, 0))

    def predict_proba(self, x):
        return np.eye(3)[self.predict(x) + 1]


def _velas_ia(n: int = 400) -> pd.DataFrame:
    close = 100 * np.exp(np.cumsum(np.random.default_rng(5).normal(0, 0.004, n)))
    return pd.DataFrame({"timestamp": np.arange(n, dtype=np.int64) * HOUR, "open": close, "high": close * 1.001,
                         "low": close * 0.999, "close": close, "volume": 1.0})


def test_el_motor_con_ia_hace_las_mismas_operaciones_que_el_backtest_con_predicciones():
    r = replay_vs_backtest(_EstrategiaIA, _velas_ia(), window=100, candles=250, ai_model=(_ModeloMomento(), None))

    assert r["backtest"] >= 6, "el escenario debe operar para que la comparación valga"
    assert not r["only_backtest"] and not r["only_rt"]


def test_la_prediccion_neutral_no_cierra_en_el_backtest_igual_que_en_vivo(monkeypatch):
    import backtest_engine

    llamadas = []
    original = backtest_engine.run_backtest_with_predictions
    monkeypatch.setattr(backtest_engine, "run_backtest_with_predictions",
                        lambda *a, **k: llamadas.append(k["hold_on_neutral"]) or original(*a, **k))

    replay_vs_backtest(_EstrategiaIA, _velas_ia(), window=100, candles=100, ai_model=(_ModeloMomento(), None))

    assert llamadas == [True]


def test_con_la_regla_antigua_el_backtest_diverge_del_motor_con_ia(monkeypatch):
    import engine_backtest

    monkeypatch.setattr(engine_backtest, "_holds_on_neutral", lambda *a: False)  # cerraba con cualquier no-1

    r = replay_vs_backtest(_EstrategiaIA, _velas_ia(), window=100, candles=250, ai_model=(_ModeloMomento(), None))

    assert r["only_backtest"] or r["only_rt"]
