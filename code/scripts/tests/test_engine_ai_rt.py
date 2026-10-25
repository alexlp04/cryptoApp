"""Tests de la inferencia en tiempo real del engine de IA."""
from __future__ import annotations

import engine_ai_rt
import numpy as np
import pytest
from engine_ai_rt import _predict_deep_learning


def test_numpy_esta_importado_en_el_modulo():
    """Regresión directa del bug #40: el módulo debe exponer `np`.

    Redundante a propósito con los tests de comportamiento: si alguien vuelve a
    mover el import a un scope local, este falla primero y señala la causa exacta
    en lugar de un `NameError` a mitad de una predicción.
    """
    assert hasattr(engine_ai_rt, "np")


class _FakeKerasModel:
    """Doble de un modelo Keras: devuelve una predicción fija con la forma pedida."""

    def __init__(self, raw: np.ndarray) -> None:
        self._raw = raw

    def predict(self, x_pred, verbose=0):
        return self._raw


# ── Rama multiclase (softmax) ────────────────────────────────────────────────

@pytest.mark.parametrize(("probs", "label_classes", "esperado"), [
    ([[0.10, 0.20, 0.70]], [-1, 0, 1], "BUY"),   # argmax=2 → clase 1
    ([[0.70, 0.20, 0.10]], [-1, 0, 1], "SELL"),  # argmax=0 → clase -1
    ([[0.20, 0.70, 0.10]], [-1, 0, 1], None),    # argmax=1 → clase 0 (hold)
])
def test_prediccion_multiclase_decodifica_la_clase(probs, label_classes, esperado):
    """La rama softmax debe decodificar el índice al espacio original de etiquetas.

    Regresión: usaba `np.argmax` sin que el módulo importase numpy, así que
    cualquier modelo de más de una clase reventaba con NameError en producción.
    """
    model = _FakeKerasModel(np.array(probs, dtype="float32"))

    accion = _predict_deep_learning(model, None, "neural_network", "BTCUSDT", label_classes)

    assert accion == esperado


def test_prediccion_multiclase_sin_label_classes_usa_fallback():
    """Sin label_classes, el índice se centra alrededor de 0: {0→-1, 1→0, 2→1}."""
    model = _FakeKerasModel(np.array([[0.10, 0.20, 0.70]], dtype="float32"))

    assert _predict_deep_learning(model, None, "neural_network", "BTCUSDT", None) == "BUY"


# ── Rama binaria (sigmoid) ───────────────────────────────────────────────────

@pytest.mark.parametrize(("prob", "esperado"), [
    (0.90, "BUY"),
    (0.10, "SELL"),
    (0.50, None),   # zona muerta entre 0.45 y 0.55
])
def test_prediccion_binaria_aplica_umbrales(prob, esperado):
    """La rama sigmoid escalar mantiene los umbrales 0.55 / 0.45."""
    model = _FakeKerasModel(np.array([[prob]], dtype="float32"))

    assert _predict_deep_learning(model, None, "neural_network", "BTCUSDT", None) == esperado


# ── ml_standard decodifica las clases codificadas (#59) ─────────────────────

class _FakeSklearnModel:
    """Modelo entrenado sobre etiquetas codificadas 0,1,2 (originales -1,0,1)."""
    classes_ = [0, 1, 2]

    def __init__(self, encoded_pred: int, probs):
        self._pred = encoded_pred
        self._probs = probs

    def predict(self, x):
        return [self._pred]

    def predict_proba(self, x):
        return [self._probs]


@pytest.mark.parametrize("encoded, esperado", [(0, "SELL"), (1, None), (2, "BUY")])
def test_ml_standard_decodifica_clases_codificadas(encoded, esperado):
    from engine_ai_rt import _predict_ml_standard

    model = _FakeSklearnModel(encoded, [0.2, 0.3, 0.5])
    assert _predict_ml_standard(model, None, "xgb", "BTCUSDT", [-1, 0, 1]) == esperado


# ── Features sin NaN→0, backfill REST y fallos del modelo (#72 #96) ──────────

import asyncio  # noqa: E402
import json  # noqa: E402
from collections import deque  # noqa: E402

import pandas as pd  # noqa: E402
import shared_utils  # noqa: E402


class _FeatStrategy:
    def __init__(self, value: float, warmup: int = 1):
        self._value = value
        self._warmup = warmup

    def populate_indicators(self, df):
        df["rsi"] = self._value
        return df

    def get_feature_columns(self, df):
        return ["rsi"]

    def get_warmup_period(self):
        return self._warmup


def _buf(n: int = 3) -> deque:
    return deque({"timestamp": i, "open": 1, "high": 1, "low": 1, "close": 1, "volume": 1} for i in range(n))


def test_features_con_nan_no_se_rellenan_con_cero():
    x = engine_ai_rt._prepare_feature_row(_buf(), _FeatStrategy(float("nan")), "BTCUSDT", "xgb")
    assert x is None


def test_features_validas_se_devuelven_como_float32():
    x = engine_ai_rt._prepare_feature_row(_buf(), _FeatStrategy(55.0), "BTCUSDT", "xgb")
    assert x.shape == (1, 1) and x.dtype == np.float32


def test_fetch_recent_klines_descarta_la_vela_abierta(monkeypatch):
    now_ms = 10_000_000.0
    monkeypatch.setattr(shared_utils.time, "time", lambda: now_ms / 1000)

    class _Resp:
        def raise_for_status(self):
            pass

        def json(self):
            cerrada = [1000, "1", "2", "0.5", "1.5", "10", 1999]
            abierta = [2000, "1", "2", "0.5", "1.5", "10", int(now_ms) + 5000]
            return [cerrada, abierta]

    monkeypatch.setattr(shared_utils.requests, "get", lambda *a, **k: _Resp())

    klines = shared_utils.fetch_recent_klines("BTC/USDT", "1m", 2)

    assert [c["timestamp"] for c in klines] == [1000]
    assert klines[0]["close"] == 1.5


class _FakeWS:
    def __init__(self, messages):
        self._messages = messages

    async def __aenter__(self):
        return self

    async def __aexit__(self, *exc):
        return False

    def __aiter__(self):
        async def gen():
            for m in self._messages:
                yield m
        return gen()


def _closed_kline(ts: int) -> str:
    return json.dumps({"E": ts, "k": {"t": ts, "T": ts + 59, "o": "1", "h": "1", "l": "1",
                                      "c": "1", "v": "1", "x": True}})


def _run_symbol(monkeypatch, strategy, messages, decide, seeded):
    monkeypatch.setattr(engine_ai_rt, "load_strategy_by_path", lambda *a, **k: strategy)
    monkeypatch.setattr(engine_ai_rt, "load_model", lambda *a: (object(), "ml_standard", None))
    monkeypatch.setattr(engine_ai_rt, "_decide_action", decide)
    monkeypatch.setattr(engine_ai_rt, "fetch_recent_klines",
                        lambda s, tf, limit: seeded.append(limit) or [])
    monkeypatch.setattr(engine_ai_rt.websockets, "connect", lambda url: _FakeWS(messages))
    return asyncio.run(engine_ai_rt.run_symbol("BTCUSDT", "1m", "x.py", "xgb", 1000.0, 0.02, False))


def test_fallo_persistente_del_modelo_termina_sin_reconectar(monkeypatch):
    def boom(*a, **k):
        raise ValueError("modelo roto")

    seeded: list = []
    messages = [_closed_kline(i * 100) for i in range(engine_ai_rt.MAX_CONSECUTIVE_FAILURES)]
    # warmup 1 → el suelo MIN_WARMUP_CANDLES (50) exige muchas velas: se siembra el buffer a mano
    monkeypatch.setattr(engine_ai_rt, "MIN_WARMUP_CANDLES", 1)

    with pytest.raises(engine_ai_rt.ModelFailure):
        _run_symbol(monkeypatch, _FeatStrategy(1.0), messages, boom, seeded)

    assert len(seeded) == 1  # una sola conexión: no hubo reconexiones


def test_el_buffer_se_siembra_con_warmup_mas_margen(monkeypatch):
    seeded: list = []
    monkeypatch.setattr(engine_ai_rt, "MIN_WARMUP_CANDLES", 1)

    async def fake_sleep(delay):
        raise KeyboardInterrupt

    monkeypatch.setattr(engine_ai_rt.asyncio, "sleep", fake_sleep)

    with pytest.raises(KeyboardInterrupt):
        _run_symbol(monkeypatch, _FeatStrategy(1.0, warmup=300), [], lambda *a, **k: None, seeded)

    assert seeded == [300 + engine_ai_rt.BACKFILL_MARGIN]
