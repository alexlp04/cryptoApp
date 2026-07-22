"""test_engine_ai_rt.py — Tests de las rutas de predicción del motor IA en tiempo real.

Cubre la regresión del bug donde ``_predict_deep_learning`` usaba ``np.argmax``
sin que ``numpy`` estuviera importado en el módulo, lo que hacía que cualquier
modelo de red neuronal (softmax multiclase) fallara con ``NameError`` en la
primera predicción y nunca emitiera señal.
"""
from __future__ import annotations

import numpy as np

import engine_ai_rt


class _FakeModel:
    """Modelo mínimo que emula la interfaz ``predict`` de Keras.

    Devuelve un array fijo, ignorando la entrada, para ejercitar la lógica de
    decodificación sin depender de TensorFlow.
    """

    def __init__(self, output: np.ndarray) -> None:
        self._output = output

    def predict(self, x, verbose=0):  # noqa: ARG002 - firma compatible con Keras
        return self._output


def test_numpy_is_imported_in_module():
    """Regresión directa: el módulo debe exponer ``np`` (numpy)."""
    assert hasattr(engine_ai_rt, "np")


def test_predict_deep_learning_multiclass_decodes_to_buy():
    # softmax de 3 clases con el máximo en el índice 2 → label_classes[2] == 1 → BUY
    model = _FakeModel(np.array([[0.2, 0.3, 0.5]]))
    action = engine_ai_rt._predict_deep_learning(
        model, x_pred=None, model_name="neural_network",
        symbol="BTCUSDT", label_classes=[-1, 0, 1],
    )
    assert action == "BUY"


def test_predict_deep_learning_multiclass_decodes_to_sell():
    # máximo en el índice 0 → label_classes[0] == -1 → SELL
    model = _FakeModel(np.array([[0.7, 0.2, 0.1]]))
    action = engine_ai_rt._predict_deep_learning(
        model, x_pred=None, model_name="neural_network",
        symbol="BTCUSDT", label_classes=[-1, 0, 1],
    )
    assert action == "SELL"


def test_predict_deep_learning_multiclass_hold_returns_none():
    # máximo en el índice 1 → label_classes[1] == 0 (HOLD) → sin señal
    model = _FakeModel(np.array([[0.2, 0.6, 0.2]]))
    action = engine_ai_rt._predict_deep_learning(
        model, x_pred=None, model_name="neural_network",
        symbol="BTCUSDT", label_classes=[-1, 0, 1],
    )
    assert action is None


def test_predict_deep_learning_multiclass_fallback_without_label_classes():
    # sin label_classes: fallback encoded_idx - (len//2); idx 2 con 3 clases → 1 → BUY
    model = _FakeModel(np.array([[0.1, 0.2, 0.7]]))
    action = engine_ai_rt._predict_deep_learning(
        model, x_pred=None, model_name="neural_network",
        symbol="BTCUSDT", label_classes=None,
    )
    assert action == "BUY"


def test_predict_deep_learning_binary_sigmoid():
    # salida escalar sigmoid: >=0.55 → BUY, <=0.45 → SELL, intermedio → None
    assert engine_ai_rt._predict_deep_learning(
        _FakeModel(np.array([[0.9]])), None, "neural_network", "BTCUSDT",
    ) == "BUY"
    assert engine_ai_rt._predict_deep_learning(
        _FakeModel(np.array([[0.1]])), None, "neural_network", "BTCUSDT",
    ) == "SELL"
    assert engine_ai_rt._predict_deep_learning(
        _FakeModel(np.array([[0.5]])), None, "neural_network", "BTCUSDT",
    ) is None
