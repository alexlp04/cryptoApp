"""shared_utils.py — Utilidades compartidas para todos los engines de trading.

Centraliza:
  - Resolución de rutas del proyecto (PROJECT_ROOT, CODE_DIR, SCRIPTS_DIR)
  - Carga de estrategias por nombre o por path de fichero
  - Setup de logging dual (archivo + stderr) unificado
  - Feature engineering compartido con apply_strategy_features
"""
from __future__ import annotations

import importlib
import importlib.util
import inspect
import logging
import os
import sys
import time
from functools import lru_cache
from typing import Any

import pandas as pd

# =============================================================================
# RUTAS DEL PROYECTO
# =============================================================================
SCRIPTS_DIR: str = os.path.dirname(os.path.abspath(__file__))
CODE_DIR: str = os.path.dirname(SCRIPTS_DIR)
PROJECT_ROOT: str = os.path.dirname(CODE_DIR)

if CODE_DIR not in sys.path:
    sys.path.insert(0, CODE_DIR)

# Mínimo de muestras limpias para entrenar/optimizar
MIN_SAMPLES_REQUIRED: int = 50
GPU_PREFERRED_MODEL_TYPES: frozenset[str] = frozenset({"xgboost"})
GPU_ERROR_TOKENS: tuple[str, ...] = (
    "cuda",
    "gpu",
    "opencl",
    "nvidia",
    "gpu_hist",
)
_LOGGED_MODEL_BACKENDS: set[tuple[str, str]] = set()
_GPU_RUNTIME_DISABLED_MODELS: set[str] = set()


# =============================================================================
# SETUP DE LOGGING UNIFICADO
# =============================================================================

def setup_engine_logging(engine_name: str, stream=None) -> logging.Logger:
    """
    Configura logging dual (archivo + stream) para un engine y devuelve su logger.

    Escribe siempre a logs/{engine_name}.log.
    El stream por defecto es sys.stderr para no contaminar stdout (canal IPC/señales).
    Solo configura el root logger si todavía no tiene handlers (evita duplicados).
    """
    if stream is None:
        stream = sys.stderr

    log_dir = os.path.join(PROJECT_ROOT, "logs")
    os.makedirs(log_dir, exist_ok=True)

    fmt_file = logging.Formatter("%(asctime)s [%(levelname)s] %(message)s")
    fmt_stream = logging.Formatter("[%(levelname)s] %(message)s")

    file_handler = logging.FileHandler(
        os.path.join(log_dir, f"{engine_name}.log"), encoding="utf-8"
    )
    file_handler.setLevel(logging.INFO)
    file_handler.setFormatter(fmt_file)

    stream_handler = logging.StreamHandler(stream)
    stream_handler.setLevel(logging.INFO)
    stream_handler.setFormatter(fmt_stream)

    root = logging.getLogger()
    if not root.handlers:
        root.setLevel(logging.INFO)
    root.addHandler(file_handler)
    root.addHandler(stream_handler)

    return logging.getLogger(engine_name)


@lru_cache(maxsize=1)
def gpu_execution_enabled() -> bool:
    """Permite desactivar GPU globalmente con CRYPTOAPP_ENABLE_GPU=0."""
    raw_value = os.getenv("CRYPTOAPP_ENABLE_GPU", "1").strip().lower()
    return raw_value not in {"0", "false", "no", "off"}


def configure_tensorflow_runtime() -> tuple[bool, list[str]]:
    """Configura TensorFlow para usar GPU cuando esté disponible."""
    import tensorflow as tf

    visible_gpus = tf.config.list_physical_devices("GPU")
    gpu_names = [gpu.name for gpu in visible_gpus]
    if not visible_gpus or not gpu_execution_enabled():
        return False, gpu_names

    for gpu in visible_gpus:
        try:
            tf.config.experimental.set_memory_growth(gpu, True)
        except RuntimeError:
            break
        except Exception as exc:  # pragma: no cover - depende del runtime CUDA real
            logging.getLogger(__name__).debug(
                "No se pudo activar memory growth para %s: %s",
                gpu.name,
                exc,
            )

    return True, gpu_names


def _apply_class_weight_if_supported(model: Any, class_weight: dict[int, float] | None) -> None:
    if class_weight is None or not hasattr(model, "class_weight"):
        return

    try:
        model.set_params(class_weight=class_weight)
    except (TypeError, ValueError):
        logging.getLogger(__name__).debug(
            "El modelo %s ignora class_weight=%s",
            type(model).__name__,
            class_weight,
        )


def _build_sample_weight(
    y_train: Any,
    class_weight: dict[int, float] | None,
) -> Any | None:
    if class_weight is None:
        return None

    try:
        import numpy as np
    except ImportError:
        return None

    y_np = np.asarray(y_train)
    return np.array([float(class_weight.get(int(label), 1.0)) for label in y_np], dtype=float)


def _fit_supports_sample_weight(model: Any) -> bool:
    try:
        return "sample_weight" in inspect.signature(model.fit).parameters
    except (TypeError, ValueError):
        return False


def _is_gpu_runtime_error(exc: Exception) -> bool:
    message = str(exc).lower()
    return any(token in message for token in GPU_ERROR_TOKENS)


def _force_cpu_params(model_type: str, params: dict[str, Any]) -> dict[str, Any]:
    cpu_params = dict(params)
    if model_type == "xgboost":
        cpu_params["device"] = "cpu"
        cpu_params.setdefault("tree_method", "hist")
    return cpu_params


def _gpu_backend_allowed(model_type: str, prefer_gpu: bool) -> bool:
    return (
        prefer_gpu
        and gpu_execution_enabled()
        and model_type not in _GPU_RUNTIME_DISABLED_MODELS
    )


def _disable_gpu_backend_for_process(model_type: str, exc: Exception) -> None:
    if model_type in _GPU_RUNTIME_DISABLED_MODELS:
        return

    _GPU_RUNTIME_DISABLED_MODELS.add(model_type)
    logging.getLogger(__name__).warning(
        "GPU no disponible para %s; reintentando en CPU: %s",
        model_type,
        exc,
    )


def _describe_model_backend(model: Any) -> str:
    module_name = type(model).__module__
    if module_name.startswith("xgboost."):
        device = getattr(model, "get_params", lambda: {})().get("device", "cpu")
        return "GPU (XGBoost CUDA)" if str(device).lower() == "cuda" else "CPU (XGBoost)"
    if module_name.startswith("lightgbm."):
        device_type = getattr(model, "get_params", lambda: {})().get("device_type", "cpu")
        return "GPU (LightGBM)" if str(device_type).lower() == "gpu" else "CPU (LightGBM)"
    if module_name.startswith("sklearn."):
        return "CPU (sklearn)"
    return f"desconocido ({module_name})"


def _log_model_backend_once(model_type: str, model: Any) -> None:
    backend = _describe_model_backend(model)
    cache_key = (model_type, backend)
    if cache_key in _LOGGED_MODEL_BACKENDS:
        return

    logging.getLogger(__name__).info(
        "Backend activo para %s: %s",
        model_type,
        backend,
    )
    _LOGGED_MODEL_BACKENDS.add(cache_key)


# =============================================================================
# CARGA DE ESTRATEGIAS
# =============================================================================

def load_strategy_by_name(
    strategy_name: str,
    capital: float = 1000.0,
    risk_per_trade: float = 0.02,
) -> Any:
    """
    Carga una estrategia por nombre de módulo y valida que herede de BaseStrategy.

    Busca primero en strategies.<strategy_name> y luego en <strategy_name>.
    """
    if not strategy_name or not strategy_name.strip():
        raise ValueError("strategy_name está vacío o no es válido.")

    strategy_name = strategy_name.strip()

    try:
        from strategies.BaseStrategy import BaseStrategy
    except Exception as exc:
        raise ValueError(f"No se pudo importar BaseStrategy: {exc}") from exc

    module = None
    errors: list[str] = []
    for module_name in (f"strategies.{strategy_name}", strategy_name):
        try:
            module = importlib.import_module(module_name)
            break
        except Exception as exc:
            errors.append(f"{module_name}: {exc}")

    if module is None:
        raise ValueError(
            f"No se pudo importar la estrategia '{strategy_name}'. "
            f"Detalle: {' | '.join(errors)}"
        )

    strategy_class = getattr(module, strategy_name, None)
    if strategy_class is None:
        raise ValueError(f"No existe la clase '{strategy_name}' en el módulo de estrategia.")

    if not isinstance(strategy_class, type) or not issubclass(strategy_class, BaseStrategy):
        raise ValueError(f"La clase '{strategy_name}' no hereda de BaseStrategy.")

    return strategy_class(capital=capital, risk_per_trade=risk_per_trade)


def load_strategy_by_path(
    path: str,
    capital: float = 1000.0,
    risk_per_trade: float = 0.02,
) -> Any:
    """
    Carga dinámicamente una estrategia desde un archivo .py y devuelve su instancia.

    Útil para backtest y realtime donde Java pasa la ruta absoluta del fichero.
    """
    try:
        from strategies.BaseStrategy import BaseStrategy
    except Exception as exc:
        raise ValueError(f"No se pudo importar BaseStrategy: {exc}") from exc

    spec = importlib.util.spec_from_file_location("user_strategy", path)
    if spec is None or spec.loader is None:
        raise ValueError(f"No se pudo cargar el fichero de estrategia: {path}")

    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)

    for obj in module.__dict__.values():
        if isinstance(obj, type) and issubclass(obj, BaseStrategy) and obj is not BaseStrategy:
            return obj(capital=capital, risk_per_trade=risk_per_trade)

    raise ValueError(
        f"No se encontró ninguna clase válida (subclase de BaseStrategy) en: {path}"
    )


# =============================================================================
# FEATURE ENGINEERING COMPARTIDO
# =============================================================================

def apply_strategy_features(
    df: pd.DataFrame,
    strategy: Any,
    warmup_candles: int,
    already_enriched: bool = False,
) -> tuple[pd.DataFrame, pd.Series]:
    """
    Extrae X (features con índice timestamp) e y (labels) de la estrategia.

    Parámetros
    ----------
    df               : DataFrame OHLCV. Si already_enriched=True, ya tiene indicadores.
    strategy         : instancia de BaseStrategy.
    warmup_candles   : velas iniciales a descartar tras populate_indicators.
    already_enriched : si True, omite populate_indicators (para optimize que ya lo hizo).
                       si False (defecto), llama populate_indicators primero.

    Devuelve
    --------
    (X_df, y_ser) con el timestamp real como índice para alineación en folds y backtests.
    """
    _logger = logging.getLogger(__name__)

    if "timestamp" not in df.columns and df.index.name == "timestamp":
        df = df.reset_index()
    if "timestamp" in df.columns:
        df = df.sort_values("timestamp").reset_index(drop=True)

    if not already_enriched:
        _logger.info(
            "Iniciando populate_indicators para estrategia '%s'", strategy.get_name()
        )
        t0 = time.perf_counter()
        df = strategy.populate_indicators(df.copy())
        _logger.info(
            "populate_indicators finalizado en %.2fs (filas=%d)",
            time.perf_counter() - t0,
            len(df),
        )

    warmup = max(int(warmup_candles or 0), 0)
    if warmup > 0:
        _logger.info("Aplicando warmup: descartando primeras %d velas", warmup)
        df = df.iloc[warmup:].reset_index(drop=True)

    if len(df) < 2:
        raise ValueError("No hay suficientes velas tras aplicar indicadores y warmup.")

    feature_cols = strategy.get_feature_columns(df)
    if not feature_cols:
        raise ValueError(
            "La estrategia no devolvió columnas de features (get_feature_columns vacío)."
        )

    current_rows = df.iloc[:-1].copy()
    next_rows = df.iloc[1:].copy()

    labels: list[int] = [
        strategy.get_label(row._asdict(), nxt._asdict())
        for row, nxt in zip(
            current_rows.itertuples(index=False, name="Row"),
            next_rows.itertuples(index=False, name="NextRow"),
        )
    ]

    ts_index = (
        pd.Index(current_rows["timestamp"].values, name="timestamp")
        if "timestamp" in current_rows.columns
        else current_rows.index
    )

    x_features = current_rows[feature_cols].copy()
    x_features.index = ts_index
    y = pd.Series(labels, index=ts_index, name="Target")

    x_features = x_features.replace([float("inf"), float("-inf")], float("nan"))
    valid_mask = ~x_features.isna().any(axis=1)
    dropped = int((~valid_mask).sum())
    if dropped > 0:
        _logger.warning("Se descartaron %d filas por NaN/inf en features.", dropped)

    x_features = x_features.loc[valid_mask]
    y = y.loc[valid_mask]

    if len(x_features) < MIN_SAMPLES_REQUIRED:
        raise ValueError(
            "No hay suficientes datos limpios tras aplicar la estrategia dinámica."
        )

    _logger.info(
        "Feature engineering completado: filas_validas=%d, features=%d, labels=%s",
        len(x_features),
        len(feature_cols),
        y.value_counts(dropna=False).to_dict(),
    )
    return x_features, y


# =============================================================================
# MODELOS ML — FÁBRICA COMPARTIDA
# =============================================================================

def build_sklearn_model(
    model_type: str,
    params: dict[str, Any],
    prefer_gpu: bool = True,
) -> Any:
    """
    Instancia un modelo sklearn / XGBoost / LightGBM con los parámetros dados.

    Filtra automáticamente los parámetros que no pertenecen a modelos sklearn
    (epochs, batch_size, etc.) para que sean seguros de pasar desde Optuna.

    GPU se usa solo para XGBoost. LightGBM, SVM y
    RandomForest quedan fijados a CPU para evitar rutas inestables o sin ventaja real.
    """
    import joblib as _jl  # noqa: F401 — importado aquí para no añadir dep al nivel de módulo

    try:
        import xgboost as xgb
        import lightgbm as lgb
        from sklearn.ensemble import RandomForestClassifier
        from sklearn.linear_model import LogisticRegression
        from sklearn.svm import SVC
    except ImportError as exc:
        raise ImportError(
            f"Dependencia no instalada para build_sklearn_model: {exc}"
        ) from exc

    # Eliminar params propios de redes neuronales que no aplican a sklearn
    nn_keys = {"epochs", "batch_size", "hidden_layers", "neurons",
               "learning_rate", "dropout_rate"}
    clean = {k: v for k, v in params.items() if k not in nn_keys}

    use_gpu = _gpu_backend_allowed(model_type, prefer_gpu)

    if model_type == "xgboost":
        clean.pop("use_label_encoder", None)  # clave obsoleta de versiones antiguas
        base = {"n_estimators": 150, "learning_rate": 0.05,
                "max_depth": 6, "random_state": 42, "eval_metric": "logloss"}
        if use_gpu:
            base.update({"tree_method": "hist", "device": "cuda"})
        else:
            base.update({"tree_method": "hist", "device": "cpu"})
        base.update(clean)
        return xgb.XGBClassifier(**base)

    if model_type == "lightgbm":
        base = {"n_estimators": 150, "learning_rate": 0.05,
                "max_depth": 6, "random_state": 42, "verbose": -1,
                "device_type": "cpu"}
        base.update(clean)
        return lgb.LGBMClassifier(**base)

    if model_type == "svm":
        base = {"kernel": "rbf", "probability": True, "random_state": 42}
        base.update(clean)
        return SVC(**base)

    if model_type == "logistic_regression":
        base = {"max_iter": 1000, "random_state": 42}
        base.update(clean)
        return LogisticRegression(**base)

    if model_type == "random_forest":
        rf_keys = {"n_estimators", "max_depth", "min_samples_split",
                   "min_samples_leaf", "max_features", "random_state"}
        rf_params = {k: v for k, v in clean.items() if k in rf_keys}
        rf_base = {"n_estimators": 100, "random_state": 42, "max_depth": 10}
        rf_base.update(rf_params)
        return RandomForestClassifier(**rf_base)

    raise ValueError(
        f"Tipo de modelo no soportado: '{model_type}'. "
        "Válidos: ['keras', 'lightgbm', 'logistic_regression', 'neural_network', "
        "'random_forest', 'svm', 'xgboost']"
    )


def fit_sklearn_model(
    model_type: str,
    params: dict[str, Any],
    x_train: Any,
    y_train: Any,
    class_weight: dict[int, float] | None = None,
) -> Any:
    """Entrena un modelo sklearn/XGBoost/LightGBM usando GPU solo donde está soportado."""
    model = build_sklearn_model(model_type, params, prefer_gpu=True)
    _log_model_backend_once(model_type, model)
    _apply_class_weight_if_supported(model, class_weight)
    sample_weight = _build_sample_weight(y_train, class_weight)
    fit_kwargs: dict[str, Any] = {}
    if sample_weight is not None and _fit_supports_sample_weight(model):
        fit_kwargs["sample_weight"] = sample_weight

    try:
        model.fit(x_train, y_train, **fit_kwargs)
        return model
    except Exception as exc:
        if model_type not in (GPU_PREFERRED_MODEL_TYPES | {"random_forest", "svm"}) or not _is_gpu_runtime_error(exc):
            raise

        _disable_gpu_backend_for_process(model_type, exc)
        cpu_model = build_sklearn_model(
            model_type,
            _force_cpu_params(model_type, params),
            prefer_gpu=False,
        )
        _log_model_backend_once(model_type, cpu_model)
        _apply_class_weight_if_supported(cpu_model, class_weight)
        cpu_fit_kwargs: dict[str, Any] = {}
        if sample_weight is not None and _fit_supports_sample_weight(cpu_model):
            cpu_fit_kwargs["sample_weight"] = sample_weight
        cpu_model.fit(x_train, y_train, **cpu_fit_kwargs)
        return cpu_model


def build_and_train_neural_network(
    X_train: "np.ndarray",
    y_train: "np.ndarray",
    X_test: "np.ndarray",
    hyperparams: dict[str, Any],
    n_classes: int = 2,
    class_weight: dict[int, float] | None = None,
) -> "tuple[Any, np.ndarray]":
    """
    Construye, entrena y evalúa una Red Neuronal Feed-Forward.

    Soporta clasificación binaria (sigmoid) y multiclase (softmax) según n_classes.
    La arquitectura es fija: Normalization → Dense(64) → Dropout → Dense(32) →
    Dropout → Dense(16) → output. Para arquitecturas configurables (Optuna) usar
    los helpers internos de engine_optimize.

    Parámetros
    ----------
    X_train      : array de entrenamiento.
    y_train      : labels de entrenamiento (enteros 0-indexed).
    X_test       : array de evaluación.
    hyperparams  : dict con keys epochs, batch_size, learning_rate, dropout_rate.
    n_classes    : número de clases (2=binario, >2=multiclase).

    Devuelve
    --------
    (model, y_pred) donde y_pred son las predicciones sobre X_test.
    """
    import numpy as np

    _logger = logging.getLogger(__name__)
    _logger.info("Importando TensorFlow/Keras para Deep Learning...")

    try:
        import tensorflow as tf  # noqa: F401
        from tensorflow.keras.callbacks import Callback, EarlyStopping
        from tensorflow.keras.layers import Dense, Dropout, Normalization
        from tensorflow.keras.models import Sequential
        from tensorflow.keras.optimizers import Adam
    except ImportError as exc:
        raise ImportError(
            "TensorFlow no está disponible. Instalar con: pip install tensorflow"
        ) from exc

    gpu_enabled, gpu_names = configure_tensorflow_runtime()
    if gpu_enabled:
        _logger.info("TensorFlow entrenará usando GPU: %s", gpu_names)
    else:
        _logger.info("TensorFlow entrenará usando CPU")

    epochs = int(hyperparams.get("epochs", 50))
    batch_size = int(hyperparams.get("batch_size", 64))
    learning_rate = float(hyperparams.get("learning_rate", 0.001))
    dropout_rate = float(hyperparams.get("dropout_rate", 0.3))

    is_multiclass = n_classes > 2
    output_units = n_classes if is_multiclass else 1
    output_activation = "softmax" if is_multiclass else "sigmoid"
    loss_fn = "sparse_categorical_crossentropy" if is_multiclass else "binary_crossentropy"

    class _EpochLogger(Callback):
        def on_epoch_end(self, epoch: int, logs: dict | None = None) -> None:
            logs = logs or {}
            _logger.info(
                "Epoch %d/%d - loss=%.4f val_loss=%.4f acc=%.4f val_acc=%.4f",
                epoch + 1, epochs,
                float(logs.get("loss") or 0.0),
                float(logs.get("val_loss") or 0.0),
                float(logs.get("accuracy") or 0.0),
                float(logs.get("val_accuracy") or 0.0),
            )

    norm_layer = Normalization()
    norm_layer.adapt(X_train)

    model = Sequential([
        norm_layer,
        Dense(64, activation="relu"),
        Dropout(dropout_rate),
        Dense(32, activation="relu"),
        Dropout(max(0.0, dropout_rate - 0.1)),
        Dense(16, activation="relu"),
        Dense(output_units, activation=output_activation),
    ])

    model.compile(
        optimizer=Adam(learning_rate=learning_rate),
        loss=loss_fn,
        metrics=["accuracy"],
    )

    _logger.info(
        "Entrenando Red Neuronal: epocas=%d batch=%d lr=%s n_clases=%d loss=%s",
        epochs, batch_size, learning_rate, n_classes, loss_fn,
    )
    es = EarlyStopping(
        monitor="val_loss", patience=5, restore_best_weights=True, verbose=0
    )
    model.fit(
        X_train, y_train,
        epochs=epochs, batch_size=batch_size,
        validation_split=0.1, verbose=0,
        callbacks=[_EpochLogger(), es],
        class_weight=class_weight,
    )

    if is_multiclass:
        y_pred = np.argmax(model.predict(X_test, verbose=0), axis=1)
    else:
        y_pred = (model.predict(X_test, verbose=0) > 0.5).astype(int).flatten()

    return model, y_pred

