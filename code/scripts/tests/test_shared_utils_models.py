"""test_shared_utils_models.py — Construcción y entrenamiento de modelos clásicos."""
from __future__ import annotations

import numpy as np
import pytest
from shared_utils import build_sklearn_model, fit_sklearn_model


@pytest.mark.parametrize("model_type", ["xgboost", "lightgbm"])
def test_learning_rate_llega_a_los_modelos_de_boosting(model_type):
    # Optuna lo optimiza para XGBoost/LightGBM; antes se descartaba en silencio (#68).
    model = build_sklearn_model(model_type, {"learning_rate": 0.29}, prefer_gpu=False)

    assert model.get_params()["learning_rate"] == 0.29


def test_learning_rate_se_sigue_filtrando_en_modelos_que_no_lo_aceptan():
    model = build_sklearn_model("logistic_regression", {"learning_rate": 0.29, "epochs": 5})

    assert "learning_rate" not in model.get_params()


def test_peso_de_clase_se_aplica_una_sola_vez():
    # class_weight del constructor × sample_weight de fit elevaba el peso al cuadrado (#69).
    rng = np.random.default_rng(0)
    x = rng.normal(size=(60, 3))
    y = np.array([0] * 50 + [1] * 10)

    model = fit_sklearn_model("random_forest", {"n_estimators": 5}, x, y, class_weight={0: 0.6, 1: 3.0})

    assert model.get_params()["class_weight"] is None


def test_un_error_de_gpu_en_random_forest_no_reintenta_ni_desactiva_la_gpu(monkeypatch):
    import shared_utils

    llamadas = []

    def fit_roto(self, x, y, **kwargs):
        llamadas.append(1)
        raise RuntimeError("CUDA error: out of memory")

    from sklearn.ensemble import RandomForestClassifier
    monkeypatch.setattr(RandomForestClassifier, "fit", fit_roto)
    shared_utils._GPU_RUNTIME_DISABLED_MODELS.clear()

    with pytest.raises(RuntimeError):
        fit_sklearn_model("random_forest", {}, np.zeros((6, 2)), np.array([0, 1, 0, 1, 0, 1]))

    assert llamadas == [1]  # sin segundo intento
    assert "random_forest" not in shared_utils._GPU_RUNTIME_DISABLED_MODELS


@pytest.mark.parametrize("nombre", ["os", "../x", "a.b", "", "  "])
def test_load_strategy_by_name_no_importa_modulos_arbitrarios(nombre):
    import shared_utils

    with pytest.raises(ValueError):
        shared_utils.load_strategy_by_name(nombre)
