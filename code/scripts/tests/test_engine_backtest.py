"""test_engine_backtest.py — Backtest con modelo IA: ventana de evaluación y un modelo por símbolo."""
from __future__ import annotations

import engine_backtest
import numpy as np
import pandas as pd
import pytest


class _Strategy:
    capital = 10_000.0
    risk_per_trade = 0.02
    timeframe = "1h"

    def get_name(self):
        return "StubStrategy"

    def get_warmup_period(self):
        return 2

    def populate_indicators(self, df):
        return df

    def get_feature_columns(self, df):
        return ["close"]

    def should_close(self, row, entry_price):
        return False


class _Model:
    def __init__(self, warmup=None, train_end_ts=None):
        self.warmup_candles = warmup
        self.train_end_ts = train_end_ts


def _df(n=10):
    return pd.DataFrame({"timestamp": np.arange(n) * 1000, "close": np.linspace(100, 110, n)})


def test_la_ventana_descarta_warmup_y_tramo_de_entrenamiento():
    df = engine_backtest._evaluation_window(_df(10), _Model(warmup=2, train_end_ts=5000), _Strategy(), False, "X")

    assert df["timestamp"].tolist() == [6000, 7000, 8000, 9000]


def test_include_train_conserva_el_tramo_de_entrenamiento_pero_no_el_warmup():
    df = engine_backtest._evaluation_window(_df(10), _Model(warmup=2, train_end_ts=5000), _Strategy(), True, "X")

    assert df["timestamp"].iloc[0] == 2000 and len(df) == 8


def test_modelo_sin_corte_evalua_todo_tras_el_warmup_de_la_estrategia():
    df = engine_backtest._evaluation_window(_df(10), _Model(), _Strategy(), False, "X")

    assert len(df) == 8  # warmup_period de la estrategia = 2


def test_cada_simbolo_carga_su_propio_modelo_y_el_que_falta_se_omite(monkeypatch, tmp_path):
    velas = {s: [{"timestamp": i * 1000, "open": 1, "high": 1, "low": 1, "close": 100 + i, "volume": 1}
                 for i in range(10)] for s in ("BTCUSDT", "ETHUSDT", "SOLUSDT")}
    payload = {"strategy_path": "x.py", "strategy_name": "S", "timeframe": "1h",
               "velas": velas, "model_name": "xgb"}
    loaded: list = []

    def fake_load_model(model_name, timeframe, symbol, strategy_name=None):
        loaded.append((symbol, strategy_name))
        if symbol == "SOLUSDT":
            raise FileNotFoundError("sin modelo")
        return _Model(warmup=1, train_end_ts=None), "ml_standard", None

    responses: list = []
    monkeypatch.setattr(engine_backtest, "read_request_payload", lambda: payload)
    monkeypatch.setattr(engine_backtest, "load_strategy_by_path", lambda *a, **k: _Strategy())
    monkeypatch.setattr(engine_backtest, "load_model", fake_load_model)
    monkeypatch.setattr(engine_backtest, "crear_carpeta_estrategia", lambda name: str(tmp_path))
    monkeypatch.setattr(engine_backtest, "_generar_predicciones",
                        lambda model, mtype, strat, df, lc: np.zeros(len(df), dtype=int))
    monkeypatch.setattr(engine_backtest, "write_response", lambda t, body: responses.append(body))

    engine_backtest.main()

    assert [s for s, _ in loaded] == ["BTCUSDT", "ETHUSDT", "SOLUSDT"]
    assert all(name == "StubStrategy" for _, name in loaded)
    assert responses[0]["skipped_symbols"] == ["SOLUSDT"]
    assert [s["symbol"] for s in responses[0]["stats"]] == ["BTCUSDT", "ETHUSDT"]


def test_main_reporta_error_si_falta_strategy_path(monkeypatch):
    errores: list = []
    monkeypatch.setattr(engine_backtest, "read_request_payload", lambda: {"timeframe": "1h"})
    monkeypatch.setattr(engine_backtest, "write_error", lambda t, msg: errores.append(msg))

    with pytest.raises(SystemExit):
        engine_backtest.main()

    assert "strategy_path" in errores[0]


# ── Decodificación binaria y datos corruptos (#101) ─────────────────────────

class _FakeNet:
    def __init__(self, raw):
        self._raw = np.asarray(raw)

    def predict(self, x, verbose=0):
        return self._raw


def test_softmax_binario_sin_label_classes_puede_predecir_compra():
    # antes: 2 columnas → índice - 1 → {-1, 0}: nunca había una compra (1)
    probs = [[0.2, 0.8], [0.9, 0.1]]

    preds = engine_backtest._generar_predicciones(_FakeNet(probs), "deep_learning", _Strategy(), _df(2), None)

    assert preds.tolist() == [1, 0]


def test_sigmoide_con_banda_neutra_no_genera_valores_basura():
    probs = [[0.9], [0.5], [0.1]]

    preds = engine_backtest._generar_predicciones(_FakeNet(probs), "deep_learning", _Strategy(), _df(3), None)

    assert preds.tolist() == [1, 0, 0]  # 0.5 cae en la banda neutra: no entrar (antes -100)


def test_velas_con_close_cero_se_descartan_en_vez_de_dividir_por_cero(monkeypatch, tmp_path):
    class BuyAll(_Strategy):
        def should_buy(self, row):
            return True

        def should_sell(self, row):
            return False

    velas = {"BTCUSDT": [
        {"timestamp": i * 1000, "open": 1, "high": 1, "low": 1, "close": 0 if i == 2 else 100 + i, "volume": 1}
        for i in range(8)]}
    payload = {"strategy_path": "x.py", "strategy_name": "S", "timeframe": "1h", "velas": velas}
    responses: list = []
    monkeypatch.setattr(engine_backtest, "read_request_payload", lambda: payload)
    monkeypatch.setattr(engine_backtest, "load_strategy_by_path", lambda *a, **k: BuyAll())
    monkeypatch.setattr(engine_backtest, "crear_carpeta_estrategia", lambda name: str(tmp_path))
    monkeypatch.setattr(engine_backtest, "write_response", lambda t, body: responses.append(body))

    engine_backtest.main()  # antes: ZeroDivisionError y SystemExit(1)

    assert responses[0]["status"] == "success"


# ── Columnas guardadas con el modelo (#62) ──────────────────────────────────

def test_las_columnas_del_modelo_mandan_sobre_las_de_la_estrategia():
    visto = {}

    class _Net(_FakeNet):
        def predict(self, x, verbose=0):
            visto["forma"] = x.shape
            return self._raw

    net = _Net([[0.2, 0.8]])
    net.feature_cols = ["close"]  # la estrategia pide otras; el modelo se entrenó solo con esta

    engine_backtest._generar_predicciones(net, "deep_learning", _Strategy(), _df(1), None)

    assert visto["forma"] == (1, 1)


def test_si_falta_una_columna_del_modelo_el_backtest_falla_en_alto():
    net = _FakeNet([[0.2, 0.8]])
    net.feature_cols = ["no_existe"]

    with pytest.raises(ValueError, match="no_existe"):
        engine_backtest._generar_predicciones(net, "deep_learning", _Strategy(), _df(1), None)
