"""Tests de la inferencia en tiempo real del engine de IA."""
from __future__ import annotations

import numpy as np
import pytest

from engine_ai_rt import _predict_deep_learning


class _FakeKerasModel:
    """Doble de un modelo Keras: devuelve una predicción fija con la forma pedida."""

    def __init__(self, raw: np.ndarray) -> None:
        self._raw = raw

    def predict(self, x_pred, verbose=0):  # noqa: ARG002 - firma impuesta por Keras
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
