"""test_engine_optimize.py — Tests unitarios del motor de optimización."""
from __future__ import annotations

import numpy as np
import optuna
import pytest
import shared_utils
from engine_optimize import _SUGGEST_FN, _prepare_optuna_sklearn_params
from shared_utils import _is_gpu_runtime_error, build_sklearn_model, fit_sklearn_model


@pytest.fixture(autouse=True)
def reset_runtime_gpu_state():
    shared_utils._GPU_RUNTIME_DISABLED_MODELS.clear()
    shared_utils._LOGGED_MODEL_BACKENDS.clear()
    yield
    shared_utils._GPU_RUNTIME_DISABLED_MODELS.clear()
    shared_utils._LOGGED_MODEL_BACKENDS.clear()


class TestOptimizationSearchSpaces:

    def test_should_treat_xgboost_cuda_crash_as_gpu_retryable(self):
        exc = RuntimeError("CUDA error: device ordinal out of range")

        assert _is_gpu_runtime_error(exc) is True

    def test_should_default_xgboost_to_gpu_backend(self):
        model = build_sklearn_model("xgboost", {})

        assert model.get_params()["device"] == "cuda"
        assert model.get_params()["tree_method"] == "hist"

    def test_should_allow_overriding_xgboost_backend_to_cpu(self):
        model = build_sklearn_model("xgboost", {"device": "cpu"})

        assert model.get_params()["device"] == "cpu"

    def test_should_default_lightgbm_to_cpu_backend(self):
        model = build_sklearn_model("lightgbm", {})

        assert model.get_params()["device_type"] == "cpu"

    def test_should_disable_xgboost_gpu_after_first_retryable_failure(self, monkeypatch):
        xgb = pytest.importorskip("xgboost")
        fit_devices: list[str] = []

        def fake_fit(self, x_train, y_train, **kwargs):
            device = self.get_params().get("device", "cpu")
            fit_devices.append(device)
            if device == "cuda":
                raise RuntimeError("CUDA error: device ordinal out of range")
            return self

        monkeypatch.setattr(xgb.XGBClassifier, "fit", fake_fit)

        rng = np.random.default_rng(42)
        x_train = rng.random((24, 4), dtype=np.float32)
        y_train = rng.integers(0, 2, size=24, dtype=np.int32)

        first_model = fit_sklearn_model("xgboost", {}, x_train, y_train)
        second_model = fit_sklearn_model("xgboost", {}, x_train, y_train)

        assert first_model.get_params()["device"] == "cpu"
        assert second_model.get_params()["device"] == "cpu"
        assert fit_devices == ["cuda", "cpu", "cpu"]

    def test_should_build_random_forest_with_cpu_backend_by_default(self):
        model = build_sklearn_model("random_forest", {})

        assert model.__class__.__name__ == "RandomForestClassifier"
        assert type(model).__module__.startswith("sklearn.")

    def test_should_build_svm_with_cpu_backend_by_default(self):
        model = build_sklearn_model("svm", {})

        assert model.__class__.__name__ == "SVC"
        assert type(model).__module__.startswith("sklearn.")

    def test_should_fit_random_forest_on_cpu_when_balancing_is_required(self):
        rng = np.random.default_rng(7)
        x_train = rng.random((80, 4), dtype=np.float32)
        y_train = np.array(([0] * 70) + ([1] * 5) + ([2] * 5), dtype=np.int32)

        model = fit_sklearn_model(
            "random_forest",
            {},
            x_train,
            y_train,
            class_weight={0: 0.3, 1: 10.0, 2: 10.0},
        )

        assert model.__class__.__name__ == "RandomForestClassifier"
        assert type(model).__module__.startswith("sklearn.")

    def test_should_not_register_gradient_boosting_search_space(self):
        assert "gradient_boosting" not in _SUGGEST_FN

    def test_should_register_svm_search_space(self):
        assert "svm" in _SUGGEST_FN

    def test_should_disable_probability_for_svm_during_optuna_search(self):
        params = {"kernel": "rbf", "C": 1.0, "gamma": "scale", "probability": True}

        optuna_params = _prepare_optuna_sklearn_params("svm", params)

        assert optuna_params["probability"] is False
        assert optuna_params["max_iter"] == 2000
        assert optuna_params["cache_size"] == 512
        assert params["probability"] is True

    def test_should_fail_when_gradient_boosting_is_requested(self):
        with pytest.raises(ValueError, match="Tipo de modelo no soportado"):
            build_sklearn_model("gradient_boosting", {})

    def test_should_build_svm_model_from_suggested_params(self):
        trial = optuna.trial.FixedTrial({
            "C": 1.5,
            "kernel": "poly",
            "gamma": "scale",
            "degree": 3,
        })

        params = _SUGGEST_FN["svm"](trial)
        model = build_sklearn_model("svm", params)

        assert model.__class__.__name__ == "SVC"
        assert model.get_params()["C"] == pytest.approx(1.5)
        assert model.get_params()["kernel"] == "poly"
        assert model.get_params()["degree"] == 3