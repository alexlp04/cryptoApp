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

# ── Clases ausentes y métricas (#79) ─────────────────────────────────────────

def test_encode_labels_descarta_del_test_las_clases_que_train_no_vio():
    from shared_utils import encode_labels

    enc, _y_train, y_test, keep = encode_labels(np.array([-1, 0, 1, 0]), np.array([0, 1, 2, -1]))

    assert enc.classes_.tolist() == [-1, 0, 1]
    assert keep.tolist() == [True, True, False, True]
    assert y_test.tolist() == [1, 2, 0]


def test_metric_average_es_binary_solo_con_etiquetas_0_1():
    from shared_utils import metric_average

    assert metric_average(np.array([0, 1]), np.array([1, 1])) == "binary"
    # 3 clases entrenadas pero test ⊂ {0, 2}: pos_label=1 no existe → no puede ser binary
    assert metric_average(np.array([0, 2]), np.array([2, 0])) == "weighted"


def test_contiguous_classes():
    from engine_optimize import _contiguous_classes

    assert _contiguous_classes(np.array([0, 1, 2, 1]))
    assert not _contiguous_classes(np.array([0, 2, 2]))


# ── Objetivo de Optuna: errores = FAIL, poda real (#90) ──────────────────────

def _objective_fixture(monkeypatch, model_type="random_forest"):
    import engine_optimize as eo
    import pandas as pd
    from sklearn.preprocessing import LabelEncoder

    rng = np.random.default_rng(1)
    n = 120
    x = rng.normal(size=(n, 3))
    y = rng.integers(0, 3, size=n)
    ts = np.arange(n, dtype=np.int64)
    df = pd.DataFrame({"timestamp": ts, "close": 1.0})
    enc = LabelEncoder().fit([-1, 0, 1])
    monkeypatch.setattr(eo, "run_backtest_with_predictions",
                        lambda *a, **k: {"win_rate": 50.0, "profit_factor": 1.0, "sharpe": 0.5})
    log: list = []
    objective = eo._make_objective(model_type, x, y, df, object(), ts, "BTCUSDT", 3, log, enc)
    return eo, objective, log


def test_un_trial_roto_queda_como_fail_y_no_como_pruned(monkeypatch):
    eo, objective, log = _objective_fixture(monkeypatch)
    monkeypatch.setattr(eo, "fit_sklearn_model", lambda *a, **k: (_ for _ in ()).throw(ValueError("boom")))

    study = optuna.create_study(direction="maximize")
    study.optimize(objective, n_trials=2, catch=(Exception,))

    estados = {t.state for t in study.trials}
    assert estados == {optuna.trial.TrialState.FAIL}
    assert "ValueError: boom" in log[0]["error"]


def test_el_objetivo_reporta_por_fold_y_el_pruner_puede_podar(monkeypatch):
    _, objective, _ = _objective_fixture(monkeypatch)

    # ThresholdPruner(lower=2): f1 ∈ [0,1] < 2 → poda en cuanto hay un report
    study = optuna.create_study(direction="maximize", pruner=optuna.pruners.ThresholdPruner(lower=2.0))
    study.optimize(objective, n_trials=2, catch=(Exception,))

    assert all(t.state == optuna.trial.TrialState.PRUNED for t in study.trials)


# ── El modelo final se entrena como se puntuó en los trials (#74) ────────────

def test_balanced_class_weight_compensa_la_clase_minoritaria():
    from engine_optimize import _balanced_class_weight

    pesos = _balanced_class_weight(np.array([0] * 9 + [1]))

    assert pesos[1] > pesos[0]
    assert pesos[1] == pytest.approx(5.0)


def test_el_modelo_sklearn_final_recibe_class_weight(monkeypatch, tmp_path):
    import engine_optimize as eo

    capturado = {}

    class _Modelo:
        def predict(self, x):
            return np.zeros(len(x), dtype=int)

    def fake_fit(model_type, params, x, y, class_weight=None):
        capturado["class_weight"] = class_weight
        return _Modelo()

    monkeypatch.setattr(eo, "fit_sklearn_model", fake_fit)
    monkeypatch.setattr(eo.joblib, "dump", lambda obj, path: open(path, "wb").close())
    y = np.array([0] * 9 + [1])

    eo._train_sklearn_final(
        "random_forest", {}, np.zeros((10, 2)), y, np.zeros((3, 2)), ["a", "b"],
        "S", 1, str(tmp_path), "1h", "BTCUSDT", None,
    )

    assert capturado["class_weight"] == eo._balanced_class_weight(y)


# ── La red neuronal multiclase se puntúa con argmax, no con umbral 0,5 (#61) ─

class _FakeKeras:
    def __init__(self, raw):
        self._raw = np.asarray(raw)

    def predict(self, x, verbose=0):
        return self._raw


def test_predict_labels_multiclase_usa_argmax():
    from engine_optimize import _predict_labels

    # con el umbral 0,5 sobre softmax esto daba [1, 1, 0] aplanado y de forma incorrecta
    probs = [[0.1, 0.2, 0.7], [0.6, 0.3, 0.1], [0.2, 0.5, 0.3]]

    assert _predict_labels(_FakeKeras(probs), None).tolist() == [2, 0, 1]


def test_predict_labels_binario_usa_umbral():
    from engine_optimize import _predict_labels

    assert _predict_labels(_FakeKeras([[0.9], [0.2]]), None).tolist() == [1, 0]


def test_el_cv_deja_un_hueco_de_una_vela_entre_train_y_validacion(monkeypatch):
    eo, objective, _ = _objective_fixture(monkeypatch)
    tamanos = []

    class _Modelo:
        def predict(self, x):
            return np.zeros(len(x), dtype=int)

    def fake_fit(model_type, params, x, y, class_weight=None):
        tamanos.append(len(x))
        return _Modelo()

    monkeypatch.setattr(eo, "fit_sklearn_model", fake_fit)
    study = optuna.create_study(direction="maximize")
    study.optimize(objective, n_trials=1, catch=(Exception,))

    # n=120, 3 folds → sin gap los trains medirían 30/60/90; con gap=1: 29/59/89
    assert tamanos[:3] == [29, 59, 89]
