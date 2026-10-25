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
import json
import logging
import logging.handlers
import os
import sys
import time
from functools import lru_cache
from typing import TYPE_CHECKING, Any

import pandas as pd
import requests

if TYPE_CHECKING:
    # Solo para anotaciones: numpy se importa de forma diferida dentro de las
    # funciones que lo necesitan para no encarecer el arranque de los engines.
    import numpy as np

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

_LOG_MAX_BYTES = 5 * 1024 * 1024
_LOG_BACKUPS = 3
_HANDLER_TAG = "_cryptoapp_engine_handler"


def setup_engine_logging(engine_name: str, stream=None) -> logging.Logger:
    """
    Configura logging dual (archivo + stream) para un engine y devuelve su logger.
    Escribe a logs/{engine_name}.log con rotación (5 MB x 3).

    Idempotente: una segunda llamada en el mismo proceso (p. ej. engine_backtest importa
    engine_ai_rt) sustituye los handlers anteriores en vez de acumularlos, así que cada línea
    sale una sola vez. Si logs/ no es escribible se degrada a solo stream en lugar de morir
    al importar el módulo.
    """
    if stream is None:
        stream = sys.stderr

    # %(process)d: varios procesos del mismo engine (una instancia de estrategia cada uno) comparten fichero
    fmt_file = logging.Formatter("%(asctime)s [%(levelname)s] [pid %(process)d] %(message)s")
    fmt_stream = logging.Formatter("[%(levelname)s] %(message)s")

    root = logging.getLogger()
    for handler in [h for h in root.handlers if getattr(h, _HANDLER_TAG, False)]:
        root.removeHandler(handler)
        handler.close()
    root.setLevel(logging.INFO)  # siempre: si una librería hizo basicConfig el nivel quedaba en WARNING

    handlers: list[logging.Handler] = []
    try:
        log_dir = os.path.join(PROJECT_ROOT, "logs")
        os.makedirs(log_dir, exist_ok=True)
        file_handler = logging.handlers.RotatingFileHandler(
            os.path.join(log_dir, f"{engine_name}.log"),
            maxBytes=_LOG_MAX_BYTES, backupCount=_LOG_BACKUPS, encoding="utf-8",
        )
        file_handler.setFormatter(fmt_file)
        handlers.append(file_handler)
    except OSError as exc:
        print(f"[WARNING] Sin log en fichero ({exc}); solo stderr", file=sys.stderr)

    stream_handler = logging.StreamHandler(stream)
    stream_handler.setFormatter(fmt_stream)
    handlers.append(stream_handler)

    for handler in handlers:
        handler.setLevel(logging.INFO)
        setattr(handler, _HANDLER_TAG, True)
        root.addHandler(handler)

    return logging.getLogger(engine_name)


# =============================================================================
# PROTOCOLO DE LÍNEA DEL CANAL RT (Python -> Java)
# =============================================================================
# El canal de tiempo real NO usa MessagePack framed (a diferencia del canal
# request/response): es NDJSON, una línea por mensaje con el formato
# ``PREFIJO\tJSON`` sobre stdout. Java lo parsea línea a línea. Este es el punto
# único donde se define el formato de las líneas de señal (ver IPC_PROTOCOL.md).
SIGNAL_PREFIX: str = "SIGNAL\t"


def upsert_candle(buffer: Any, candle: dict) -> None:
    """Los ticks de la vela abierta comparten open_time: sustituyen a la última, no se añaden."""
    if buffer and buffer[-1]["timestamp"] == candle["timestamp"]:
        buffer[-1] = candle
    else:
        buffer.append(candle)


def fetch_recent_klines(symbol: str, timeframe: str, limit: int = 500) -> list[dict]:
    """Últimas velas CERRADAS por REST (para sembrar el buffer RT y cerrar huecos al reconectar)."""
    params: dict[str, str | int] = {
        "symbol": symbol.replace("/", "").upper(), "interval": timeframe, "limit": limit}
    response = requests.get("https://api.binance.com/api/v3/klines", params=params, timeout=10)
    response.raise_for_status()
    now_ms = time.time() * 1000
    return [
        {"timestamp": int(c[0]), "open": float(c[1]), "high": float(c[2]),
         "low": float(c[3]), "close": float(c[4]), "volume": float(c[5])}
        for c in response.json() if c[6] < now_ms  # closeTime pasada = vela cerrada
    ]


def encode_labels(y_train_raw: Any, y_test_raw: Any) -> tuple[Any, Any, Any, Any]:
    """Codifica labels a 0..K-1 con las clases de train y descarta del test las que no vio.

    Devuelve (encoder, y_train, y_test, keep): keep es la máscara de filas de test conservadas
    (el llamador debe filtrar con ella X_test y los timestamps). Sin esto, una clase rara que
    solo aparece en el tramo final aborta la ejecución con 'unseen labels'.
    """
    import numpy as np
    from sklearn.preprocessing import LabelEncoder

    encoder = LabelEncoder()
    y_train = encoder.fit_transform(y_train_raw)
    y_test_raw = np.asarray(y_test_raw)
    keep = np.isin(y_test_raw, encoder.classes_)
    if not keep.all():
        logging.getLogger(__name__).warning(
            "Test: %d filas con clases ausentes en train (%s) descartadas",
            int((~keep).sum()), sorted(set(y_test_raw[~keep].tolist())),
        )
    return encoder, y_train, encoder.transform(y_test_raw[keep]), keep


def metric_average(*labels: Any) -> str:
    """'binary' solo si todas las etiquetas son 0/1 (pos_label=1 existe); si no, 'weighted'."""
    import numpy as np

    present = set(np.unique(np.concatenate([np.asarray(x).ravel() for x in labels])).tolist())
    return "binary" if present <= {0, 1} else "weighted"


def atomic_save(path: str, save_fn: Any) -> None:
    """Guarda en un temporal y renombra: un RT que lee el modelo nunca ve un fichero a medias."""
    base, ext = os.path.splitext(path)
    tmp = f"{base}.tmp{ext}"  # conserva la extensión: Keras decide el formato por ella
    try:
        save_fn(tmp)
        os.replace(tmp, path)
    finally:
        if os.path.exists(tmp):
            os.remove(tmp)


class ModelIntegrityError(ValueError):
    """El fichero del modelo no es el que se guardó (manipulado o corrupto) o viene de otras librerías."""


#: Librerías cuyo formato interno cambia entre versiones menores y afecta a un modelo guardado.
_MODEL_LIBRARIES = ("scikit-learn", "xgboost", "lightgbm", "tensorflow")


def _manifest_path(model_path: str) -> str:
    return os.path.splitext(model_path)[0] + ".manifest.json"


def _sha256_of(path: str) -> str:
    import hashlib

    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _installed_versions() -> dict[str, str]:
    from importlib import metadata

    found: dict[str, str] = {}
    for lib in _MODEL_LIBRARIES:
        try:
            found[lib] = metadata.version(lib)
        except metadata.PackageNotFoundError:
            continue
    return found


def save_model(path: str, save_fn: Any) -> None:
    """atomic_save + manifiesto con el sha256 y las versiones de las librerías (issues #33 y #93).

    shortcut: el sha256 detecta ficheros corruptos o cambiados por error, no a quien reescriba
    también el manifiesto; firmarlo con una clave (HMAC) si los modelos se comparten entre máquinas.
    """
    atomic_save(path, save_fn)
    manifest = {"sha256": _sha256_of(path), "libraries": _installed_versions()}
    atomic_save(_manifest_path(path), lambda tmp: _write_json(tmp, manifest))


def _write_json(path: str, content: dict[str, Any]) -> None:
    with open(path, "w", encoding="utf-8") as f:
        json.dump(content, f, indent=2)


def _major_minor(version: str) -> str:
    return ".".join(version.split(".")[:2])


def verify_model_manifest(model_path: str) -> None:
    """Rechaza un modelo cuyo contenido no coincide con su manifiesto o que se guardó con otra
    versión (mayor.menor) de sklearn/xgboost/lightgbm/tensorflow. Sin manifiesto (modelo anterior
    a esta comprobación) solo avisa: se carga igual."""
    manifest_file = _manifest_path(model_path)
    if not os.path.exists(manifest_file):
        logging.getLogger(__name__).warning(
            "El modelo %s no tiene manifiesto: no se puede comprobar su integridad. Reentrénalo.",
            os.path.basename(model_path))
        return
    with open(manifest_file, encoding="utf-8") as f:
        manifest = json.load(f)
    if manifest.get("sha256") != _sha256_of(model_path):
        raise ModelIntegrityError(
            f"El modelo {os.path.basename(model_path)} no coincide con su manifiesto: "
            "está corrupto o lo han modificado. Reentrénalo.")
    actuales = _installed_versions()
    for lib, guardada in manifest.get("libraries", {}).items():
        actual = actuales.get(lib)
        if actual is not None and _major_minor(actual) != _major_minor(guardada):
            raise ModelIntegrityError(
                f"El modelo {os.path.basename(model_path)} se guardó con {lib} {guardada} y ahora "
                f"está instalada la {actual}: el formato puede haber cambiado. Reentrénalo.")


def build_signal_line(signal: dict) -> str:
    """Construye una línea de señal RT para stdout (``SIGNAL\\tJSON``)."""
    return SIGNAL_PREFIX + json.dumps(signal)


# =============================================================================
# HEARTBEAT DEL CANAL RT (liveness)
# =============================================================================
HEARTBEAT_PREFIX: str = "HEARTBEAT\t"


def build_heartbeat_line() -> str:
    """Construye una línea de heartbeat para stdout (canal RT).
    Java la usa como señal de vida: mientras lleguen heartbeats, el proceso
    está sano aunque no emita señales. 
    """
    return HEARTBEAT_PREFIX + json.dumps({"type": "heartbeat"})


async def emit_heartbeats(interval_seconds: float = 15.0) -> None:
    """Task asíncrona que emite un heartbeat por stdout cada ``interval_seconds``.
    Debe lanzarse junto a los streams de símbolos para que el canal RT nunca
    quede en silencio durante operación sana.
    """
    import asyncio

    while True:
        await asyncio.sleep(interval_seconds)
        print(build_heartbeat_line(), flush=True)


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
        except Exception as exc:  # noqa: BLE001 # pragma: no cover - runtime CUDA real
            logging.getLogger(__name__).debug(
                "No se pudo activar memory growth para %s: %s",
                gpu.name,
                exc,
            )

    return True, gpu_names


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

    # Se recorre el diccionario de clases (unas pocas) en vez de las muestras (muchas):
    # esto se ejecuta en cada fit, es decir por fold y por trial de Optuna.
    y_np = np.asarray(y_train)
    pesos = np.ones(len(y_np), dtype=float)
    for clase, peso in class_weight.items():
        pesos[y_np == clase] = float(peso)
    return pesos


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
    # El fallback `dict` cubre modelos sin get_params (dobles de test, por ejemplo);
    # se anota porque sin ello el tipo inferido es dict[Never, Never] y .get() no encaja.
    params: dict[str, Any] = getattr(model, "get_params", dict)()
    if module_name.startswith("xgboost."):
        device = params.get("device", "cpu")
        return "GPU (XGBoost CUDA)" if str(device).lower() == "cuda" else "CPU (XGBoost)"
    if module_name.startswith("lightgbm."):
        device_type = params.get("device_type", "cpu")
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

    Solo busca en strategies.<strategy_name>: el segundo intento anterior (importar el nombre "a pelo")
    cargaba cualquier módulo del sys.path (p. ej. "os") con un nombre que llega desde Java.
    """
    if not strategy_name or not strategy_name.strip():
        raise ValueError("strategy_name está vacío o no es válido.")

    strategy_name = strategy_name.strip()
    if not strategy_name.isidentifier():
        raise ValueError(f"Nombre de estrategia no válido: {strategy_name!r}")

    try:
        from strategies.BaseStrategy import BaseStrategy
    except Exception as exc:
        raise ValueError(f"No se pudo importar BaseStrategy: {exc}") from exc

    module = None
    errors: list[str] = []
    for module_name in (f"strategies.{strategy_name}",):
        try:
            module = importlib.import_module(module_name)
            break
        except Exception as exc:  # noqa: BLE001 - se prueban varias rutas antes de rendirse
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
        raise ValueError(  # noqa: TRY004 - coherente con las validaciones vecinas
            f"La clase '{strategy_name}' no hereda de BaseStrategy."
        )

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

    module_name = f"user_strategy_{os.path.splitext(os.path.basename(path))[0]}"
    spec = importlib.util.spec_from_file_location(module_name, path)
    if spec is None or spec.loader is None:
        raise ValueError(f"No se pudo cargar el fichero de estrategia: {path}")

    module = importlib.util.module_from_spec(spec)
    # Registrado antes de ejecutarlo: pickle, dataclasses y typing resuelven la clase por su módulo.
    sys.modules[module_name] = module
    try:
        spec.loader.exec_module(module)
    except BaseException:
        sys.modules.pop(module_name, None)
        raise

    # Solo clases DEFINIDAS en el fichero y concretas: la primera subclase del módulo podía ser una
    # estrategia importada, una base intermedia o una clase abstracta.
    candidates = [
        obj for obj in module.__dict__.values()
        if isinstance(obj, type) and issubclass(obj, BaseStrategy) and obj is not BaseStrategy
        and obj.__module__ == module.__name__ and not inspect.isabstract(obj)
    ]
    if len(candidates) > 1:
        names = ", ".join(c.__name__ for c in candidates)
        raise ValueError(f"Hay varias estrategias concretas en {path}: {names}. Deja una por fichero.")
    if not candidates:
        raise ValueError(
            f"No se encontró ninguna clase válida (subclase concreta de BaseStrategy) en: {path}"
        )
    return candidates[0](capital=capital, risk_per_trade=risk_per_trade)


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
        import lightgbm as lgb
        import xgboost as xgb
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
    if model_type in ("xgboost", "lightgbm"):
        nn_keys = nn_keys - {"learning_rate"}  # hiperparámetro propio de los árboles con boosting
    clean = {k: v for k, v in params.items() if k not in nn_keys}

    use_gpu = _gpu_backend_allowed(model_type, prefer_gpu)

    if model_type == "xgboost":
        clean.pop("use_label_encoder", None)  # clave obsoleta de versiones antiguas
        # Bolsa heterogénea de hiperparámetros (str/int/float/bool): sin la anotación
        # se infiere dict[str, object] y el desempaquetado **base choca con las firmas
        # tipadas de los estimadores.
        base: dict[str, Any] = {"n_estimators": 150, "learning_rate": 0.05,
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
    """Entrena un modelo sklearn/XGBoost/LightGBM usando GPU solo donde está soportado.

    El peso de clase se aplica solo vía sample_weight: sklearn y LightGBM multiplican
    class_weight por sample_weight, así que usar ambos elevaría el peso al cuadrado.
    """
    model = build_sklearn_model(model_type, params, prefer_gpu=True)
    _log_model_backend_once(model_type, model)
    sample_weight = _build_sample_weight(y_train, class_weight)
    fit_kwargs: dict[str, Any] = {}
    if sample_weight is not None and _fit_supports_sample_weight(model):
        fit_kwargs["sample_weight"] = sample_weight

    try:
        model.fit(x_train, y_train, **fit_kwargs)
        return model
    except Exception as exc:
        # random_forest/svm nunca usan GPU: reintentarlos "por GPU" entrenaba dos veces y desactivaba la GPU para todo el proceso
        if model_type not in GPU_PREFERRED_MODEL_TYPES or not _is_gpu_runtime_error(exc):
            raise

        _disable_gpu_backend_for_process(model_type, exc)
        cpu_model = build_sklearn_model(
            model_type,
            _force_cpu_params(model_type, params),
            prefer_gpu=False,
        )
        _log_model_backend_once(model_type, cpu_model)
        cpu_fit_kwargs: dict[str, Any] = {}
        if sample_weight is not None and _fit_supports_sample_weight(cpu_model):
            cpu_fit_kwargs["sample_weight"] = sample_weight
        cpu_model.fit(x_train, y_train, **cpu_fit_kwargs)
        return cpu_model


def build_and_train_neural_network(
    X_train: np.ndarray,
    y_train: np.ndarray,
    X_test: np.ndarray,
    hyperparams: dict[str, Any],
    n_classes: int = 2,
    class_weight: dict[int, float] | None = None,
) -> tuple[Any, np.ndarray]:
    """
    Construye, entrena y evalúa una Red Neuronal Feed-Forward.

    Soporta clasificación binaria (sigmoid) y multiclase (softmax) según n_classes.
    La arquitectura es fija: Normalization → Dense(64) → Dropout → Dense(32) →
    Dropout → Dense(16) → output. Para arquitecturas configurables (Optuna) usar
    los helpers internos de engine_optimize.
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

