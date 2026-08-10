"""
engine_optimize.py — Motor de búsqueda de hiperparámetros óptimos con Optuna.

Protocolo IPC:
  Entrada : MessagePack framed (4 bytes big-endian + envelope) con type OPTIMIZE_REQUEST
  Salida  : MessagePack framed (4 bytes big-endian + envelope) con type OPTIMIZE_RESPONSE

Payload de entrada (desde AIOptimizationService.java):
  {
        "model_type":    str     — xgboost | lightgbm | random_forest | svm | neural_network
    "symbol":        str     — ej. "BTCUSDT"
    "timeframe":     str     — ej. "1h"
    "dataset":       list    — lista de rows {timestamp, open?, high?, low?, close, volume, indics…}
    "min_composite": float   — mínimo de composite score para considerar éxito (fracción, ej. 0.60)
    "min_accuracy":  float   — DEPRECATED: alias de min_composite para compatibilidad
    "n_trials":      int     — número máximo de trials Optuna (default 100)
    "cv_folds":      int     — k en k-fold CV (default 5)
    "strategy_name": str?    — nombre de la estrategia Python (opcional)
    "warmup_candles":int?    — velas de calentamiento (solo con strategy_name)
  }

Payload de salida:
  {
    "status":          "success" | "warning" | "error"
    "model_saved_at":  str     — nombre del fichero guardado en models/
    "best_params":     dict    — hiperparámetros óptimos encontrados
    "best_accuracy_cv":float   — mejor accuracy en cross-validation
    "best_accuracy_train": float
    "overfit_gap":     float   — diferencia train_acc - cv_acc
    "min_accuracy_reached": bool
    "trials_completed":int
    "trials_total":    int
    "feature_cols":    list[str]
    "strategy_name":   str?
    "warmup_candles":  int?
    "label_distribution": dict
    "csv_path":        str     — ruta relativa del CSV de resultados
    "all_trials":      list[dict]   — resumen de todos los trials
    "data_info":       dict    — información del dataset
  }
"""
from __future__ import annotations

import gc
import json
import math
import os
import sys
import warnings
from datetime import datetime
from typing import Any

import joblib
import numpy as np
import optuna
import pandas as pd
from backtest_engine import run_backtest_with_predictions
from ipc_protocol import read_request_payload, write_error, write_response
from optuna.pruners import MedianPruner
from shared_utils import (
    PROJECT_ROOT,
    apply_strategy_features,
    configure_tensorflow_runtime,
    fit_sklearn_model,
    load_strategy_by_name,
    setup_engine_logging,
)
from sklearn.metrics import accuracy_score, f1_score, precision_score, recall_score
from sklearn.model_selection import TimeSeriesSplit
from sklearn.preprocessing import LabelEncoder
from sklearn.utils.class_weight import compute_class_weight

warnings.filterwarnings("ignore", category=UserWarning)
optuna.logging.set_verbosity(optuna.logging.WARNING)

# =============================================================================
# CONFIGURACIÓN DE LOGS
# =============================================================================
logger = setup_engine_logging("engine_optimize")
project_root = PROJECT_ROOT  # alias utilizado en el módulo para rutas de models/ y results/

# =============================================================================
# CONSTANTES
# =============================================================================
DEFAULT_N_TRIALS: int = 100
DEFAULT_CV_FOLDS: int = 5
DEFAULT_MIN_COMPOSITE: float = 0.55

# Almacén persistente de estudios Optuna. Sin él los estudios viven solo en memoria y
# se pierden al terminar el proceso, impidiendo comparar ejecuciones o inspeccionarlas
# con optuna-dashboard. Sobrescribible por entorno para apuntar a otro backend.
OPTUNA_STORAGE_ENV_VAR: str = "CRYPTOAPP_OPTUNA_STORAGE"
DEFAULT_OPTUNA_DB_RELPATH: str = os.path.join("results", "optuna", "studies.db")
MIN_SAMPLES_REQUIRED: int = 50
RESULTS_SUBDIR: str = "optimize"
# Máximo de filas usadas durante la búsqueda con Optuna para modelos NN.
# El entrenamiento final siempre usa el dataset completo.
OPTUNA_NN_MAX_SAMPLES: int = 50_000
OPTUNA_SVM_MAX_SAMPLES: int = 12_000


# =============================================================================
# PERSISTENCIA DE ESTUDIOS OPTUNA
# =============================================================================

def resolve_optuna_storage(root: str | None = None) -> str | None:
    """
    Devuelve la URL del almacén persistente de estudios Optuna.

    Prioriza la variable de entorno CRYPTOAPP_OPTUNA_STORAGE (útil para apuntar a
    MySQL/PostgreSQL); si no está, usa un SQLite bajo results/optuna/.

    Devuelve None cuando el directorio no se puede preparar: en ese caso el estudio
    corre en memoria, que es peor para reproducibilidad pero preferible a abortar
    una optimización de horas por un problema de disco.
    """
    override = os.environ.get(OPTUNA_STORAGE_ENV_VAR, "").strip()
    if override:
        return override

    db_path = os.path.join(root or project_root, DEFAULT_OPTUNA_DB_RELPATH)
    try:
        os.makedirs(os.path.dirname(db_path), exist_ok=True)
    except OSError as exc:
        logger.warning(
            "No se pudo preparar el almacén Optuna (%s); el estudio correrá en memoria", exc,
        )
        return None

    return "sqlite:///" + db_path.replace("\\", "/")


def build_study_name(model_type: str, symbol: str, timeframe: str,
                     moment: datetime | None = None) -> str:
    """
    Nombre único por ejecución.

    Se incluye timestamp a propósito: reutilizar el nombre haría que Optuna acumulase
    trials de datasets o rangos distintos en el mismo estudio, invalidando cualquier
    comparación posterior.
    """
    stamp = (moment or datetime.now()).strftime("%Y%m%d-%H%M%S")
    return f"optimize_{model_type}_{symbol}_{timeframe}_{stamp}"


# =============================================================================
# FUNCIÓN OBJETIVO COMPUESTA (multi-métrica)
# =============================================================================

def _compute_composite_score(
    f1_cv: float,
    win_rate_frac: float,
    pf_norm: float,
    sharpe_norm: float,
    overfit_gap: float,
) -> float:
    """
    Función de scoring compuesta (maximizar).
    Combina calidad de clasificación + métricas de trading reales.
    Penaliza overfitting.

    win_rate_frac: fracción 0-1 (no porcentaje).
    pf_norm / sharpe_norm: ya normalizados a [0, 1].
    """
    base = (
        0.25 * f1_cv           # calidad de clasificación (reducido: ya no domina el total)
        + 0.40 * win_rate_frac  # % de trades ganadores (clave para rentabilidad real)
        + 0.25 * pf_norm        # profit factor normalizado
        + 0.10 * sharpe_norm    # retorno ajustado a riesgo
    )
    penalty = max(0.0, overfit_gap - 0.05) * 2.0
    return base - penalty


# =============================================================================
# CARGA DE ESTRATEGIA (reutiliza misma lógica que engine_train.py)
# =============================================================================

# load_strategy y apply_strategy_features provienen de shared_utils.
# Alias para compatibilidad con las llamadas internas de este módulo.
load_strategy = load_strategy_by_name


# =============================================================================
# ESPACIOS DE BÚSQUEDA DE HIPERPARÁMETROS POR MODELO
# =============================================================================

def _suggest_xgboost(trial: optuna.Trial) -> dict[str, Any]:
    return {
        "n_estimators": trial.suggest_int("n_estimators", 50, 500),
        "learning_rate": trial.suggest_float("learning_rate", 0.01, 0.3, log=True),
        "max_depth": trial.suggest_int("max_depth", 3, 12),
        "subsample": trial.suggest_float("subsample", 0.6, 1.0),
        "colsample_bytree": trial.suggest_float("colsample_bytree", 0.6, 1.0),
        "gamma": trial.suggest_float("gamma", 0.0, 5.0),
        "reg_alpha": trial.suggest_float("reg_alpha", 1e-8, 10.0, log=True),
        "reg_lambda": trial.suggest_float("reg_lambda", 1e-8, 10.0, log=True),
        "min_child_weight": trial.suggest_int("min_child_weight", 1, 10),
        "random_state": 42,
        "eval_metric": "logloss",
        "use_label_encoder": False,
    }


def _suggest_lightgbm(trial: optuna.Trial) -> dict[str, Any]:
    return {
        "n_estimators": trial.suggest_int("n_estimators", 50, 500),
        "learning_rate": trial.suggest_float("learning_rate", 0.01, 0.3, log=True),
        "max_depth": trial.suggest_int("max_depth", 3, 12),
        "num_leaves": trial.suggest_int("num_leaves", 15, 127),
        "min_child_samples": trial.suggest_int("min_child_samples", 5, 50),
        "subsample": trial.suggest_float("subsample", 0.6, 1.0),
        "colsample_bytree": trial.suggest_float("colsample_bytree", 0.6, 1.0),
        "reg_alpha": trial.suggest_float("reg_alpha", 1e-8, 10.0, log=True),
        "reg_lambda": trial.suggest_float("reg_lambda", 1e-8, 10.0, log=True),
        "random_state": 42,
        "verbose": -1,
    }


def _suggest_random_forest(trial: optuna.Trial) -> dict[str, Any]:
    return {
        "n_estimators": trial.suggest_int("n_estimators", 50, 500),
        "max_depth": trial.suggest_int("max_depth", 3, 30),
        "min_samples_split": trial.suggest_int("min_samples_split", 2, 20),
        "min_samples_leaf": trial.suggest_int("min_samples_leaf", 1, 10),
        "max_features": trial.suggest_categorical("max_features", ["sqrt", "log2", None]),
        "random_state": 42,
    }


def _suggest_svm(trial: optuna.Trial) -> dict[str, Any]:
    kernel = trial.suggest_categorical("kernel", ["linear", "rbf", "poly", "sigmoid"])
    params: dict[str, Any] = {
        "C": trial.suggest_float("C", 1e-3, 1e3, log=True),
        "kernel": kernel,
        "gamma": trial.suggest_categorical("gamma", ["scale", "auto"]),
        "probability": True,
        "random_state": 42,
    }
    if kernel == "poly":
        params["degree"] = trial.suggest_int("degree", 2, 5)
    return params


def _prepare_svm_fit_params(params: dict[str, Any]) -> dict[str, Any]:
    """Normaliza el fit de SVM para evitar calibraciones internas muy costosas."""
    fit_params = dict(params)
    # `probability=True` activa una calibración interna adicional en libsvm que,
    # para el entrenamiento final, puede dejar el proceso más de una hora sin emitir logs.
    fit_params["probability"] = False
    fit_params.setdefault("cache_size", 512)
    fit_params.setdefault("max_iter", 2000)
    return fit_params


def _prepare_optuna_sklearn_params(model_type: str, params: dict[str, Any]) -> dict[str, Any]:
    """Ajustes de búsqueda para mantener Optuna ágil sin afectar el modelo final guardado."""
    if model_type != "svm":
        return params

    return _prepare_svm_fit_params(params)


def _suggest_neural_network(trial: optuna.Trial) -> dict[str, Any]:
    return {
        "epochs": trial.suggest_int("epochs", 20, 200),
        "batch_size": trial.suggest_categorical("batch_size", [32, 64, 128, 256]),
        "learning_rate": trial.suggest_float("learning_rate", 1e-4, 1e-2, log=True),
        "dropout_rate": trial.suggest_float("dropout_rate", 0.1, 0.5),
        "hidden_layers": trial.suggest_int("hidden_layers", 1, 3),
        "neurons": trial.suggest_categorical("neurons", [16, 32, 64, 128]),
    }


_SUGGEST_FN: dict[str, Any] = {
    "xgboost": _suggest_xgboost,
    "lightgbm": _suggest_lightgbm,
    "random_forest": _suggest_random_forest,
    "svm": _suggest_svm,
    "neural_network": _suggest_neural_network,
    "deep_learning": _suggest_neural_network,
    "keras": _suggest_neural_network,
}


# =============================================================================
# CONSTRUCCIÓN DE MODELO POR TIPO
# =============================================================================

def _build_and_eval_neural_network(
    X: np.ndarray,
    y: np.ndarray,
    params: dict[str, Any],
    cv_folds: int,
    df_enriched: pd.DataFrame | None = None,
    strategy: Any | None = None,
    timestamps: np.ndarray | None = None,
    label_encoder: Any = None,
    symbol: str = "UNKNOWN",
    optuna_max_samples: int = OPTUNA_NN_MAX_SAMPLES,
) -> tuple[float, float, float, float, float, float]:
    """
    Entrena una red neuronal con los parámetros dados y devuelve
    (cv_accuracy_mean, train_accuracy, f1_cv, win_rate_cv, pf_norm_cv, sharpe_norm_cv).
    No usa cross_val_score (Keras no es compatible) → hace k-fold manual.
    Cuando se proporcionan df_enriched, strategy, timestamps y label_encoder,
    ejecuta un backtest por fold para obtener métricas de trading reales.

    Para la fase de búsqueda Optuna usa como máximo `optuna_max_samples` filas
    (muestra estratificada de las últimas filas) para reducir el tiempo del trial.
    El entrenamiento final llama a esta función con el dataset completo.
    """
    import tensorflow as tf  # importación diferida para evitar overhead cuando no se usa
    from tensorflow.keras.callbacks import EarlyStopping
    from tensorflow.keras.layers import Dense, Dropout, Normalization
    from tensorflow.keras.models import Sequential
    from tensorflow.keras.optimizers import Adam

    gpu_enabled, gpu_names = configure_tensorflow_runtime()
    if gpu_enabled:
        logger.info("TensorFlow/Optuna usará GPU: %s", gpu_names)
    else:
        logger.info("TensorFlow/Optuna usará CPU")

    epochs = int(params.get("epochs", 50))
    batch_size = int(params.get("batch_size", 64))
    lr = float(params.get("learning_rate", 0.001))
    dropout = float(params.get("dropout_rate", 0.3))
    hidden_layers = int(params.get("hidden_layers", 2))
    neurons = int(params.get("neurons", 64))

    # Submuestreo estratégico para acelerar la fase de búsqueda Optuna.
    # Las últimas filas son más representativas del periodo reciente.
    if len(X) > optuna_max_samples:
        logger.debug(
            "Subsampling NN Optuna: %d → %d filas (optuna_max_samples)",
            len(X), optuna_max_samples,
        )
        X = X[-optuna_max_samples:]
        y = y[-optuna_max_samples:]
        if timestamps is not None:
            timestamps = timestamps[-optuna_max_samples:]

    n_classes_nn = len(np.unique(y))
    is_multiclass = n_classes_nn > 2
    output_units = n_classes_nn if is_multiclass else 1
    output_activation = "softmax" if is_multiclass else "sigmoid"
    loss_fn = "sparse_categorical_crossentropy" if is_multiclass else "binary_crossentropy"

    # class_weight para compensar el desbalanceo de etiquetas (problema 1)
    raw_classes = np.unique(y)
    cw = compute_class_weight("balanced", classes=raw_classes, y=y)
    class_weight_dict = dict(zip(raw_classes.tolist(), cw.tolist()))
    logger.debug("class_weight (NN): %s", class_weight_dict)

    # TimeSeriesSplit garantiza que cada fold de validación es siempre posterior
    # al conjunto de entrenamiento, respetando la causalidad de las series temporales.
    tss = TimeSeriesSplit(n_splits=cv_folds)
    cv_scores: list[float] = []
    cv_f1_scores: list[float] = []
    cv_win_rates: list[float] = []
    cv_pf_norms: list[float] = []
    cv_sharpe_norms: list[float] = []
    has_backtest_data = (
        df_enriched is not None
        and strategy is not None
        and timestamps is not None
        and label_encoder is not None
    )
    n_classes_nn_cur = len(np.unique(y))
    avg_mode_nn = "binary" if n_classes_nn_cur <= 2 else "weighted"

    # Guardamos las predicciones del último fold para ejecutar UN SOLO backtest al final
    last_y_pred_fold: np.ndarray | None = None
    last_val_idx_nn: np.ndarray | None = None

    for fold_idx, (train_idx, val_idx) in enumerate(tss.split(X)):
        x_tr, x_val = X[train_idx], X[val_idx]
        y_tr, y_val = y[train_idx], y[val_idx]

        norm = Normalization()
        norm.adapt(x_tr)

        layers: list[Any] = [norm]
        for _ in range(hidden_layers):
            layers.append(Dense(neurons, activation="relu"))
            layers.append(Dropout(dropout))
        layers.append(Dense(output_units, activation=output_activation))

        model = Sequential(layers)
        model.compile(
            optimizer=Adam(learning_rate=lr),
            loss=loss_fn,
            metrics=["accuracy"],
        )
        # EarlyStopping para evitar sobreentrenamiento y reducir tiempo por trial
        es_fold = EarlyStopping(
            monitor="val_loss", patience=5, restore_best_weights=True, verbose=0
        )
        model.fit(
            x_tr, y_tr,
            epochs=epochs, batch_size=batch_size,
            validation_split=0.1,
            callbacks=[es_fold],
            class_weight=class_weight_dict,
            verbose=0,
        )
        if is_multiclass:
            y_pred_fold = np.argmax(model.predict(x_val, verbose=0), axis=1)
        else:
            y_pred_fold = (model.predict(x_val, verbose=0) > 0.5).astype(int).flatten()

        fold_acc = float(accuracy_score(y_val, y_pred_fold))
        fold_f1 = float(f1_score(y_val, y_pred_fold, average=avg_mode_nn, zero_division=0))
        cv_scores.append(fold_acc)
        cv_f1_scores.append(fold_f1)
        last_y_pred_fold = y_pred_fold
        last_val_idx_nn = val_idx

        logger.debug("NN fold %d/%d — val_acc=%.4f f1=%.4f", fold_idx + 1, cv_folds, fold_acc, fold_f1)
        tf.keras.backend.clear_session()
        del x_tr, x_val, y_tr, y_val
        gc.collect()

    # Backtest UNA VEZ sobre el último fold (datos más recientes — respeta causalidad)
    if has_backtest_data and last_y_pred_fold is not None and last_val_idx_nn is not None:
        y_pred_original = label_encoder.inverse_transform(last_y_pred_fold)  # type: ignore[union-attr]
        val_timestamps = timestamps[last_val_idx_nn]  # type: ignore[index]
        df_val = df_enriched[
            df_enriched["timestamp"].isin(set(val_timestamps.tolist()))  # type: ignore[index]
        ].sort_values("timestamp").reset_index(drop=True)
        sim = run_backtest_with_predictions(strategy, df_val, y_pred_original, symbol)
        cv_win_rates.append(sim["win_rate"] / 100.0)
        cv_pf_norms.append(min(sim["profit_factor"], 5.0) / 5.0)
        cv_sharpe_norms.append(max(0.0, min(sim.get("sharpe", 0.0) / 2.0, 1.0)))
        logger.debug(
            "  NN backtest (último fold) — win_rate=%.2f%% pf=%.4f sharpe=%.4f",
            sim["win_rate"], sim["profit_factor"], sim.get("sharpe", 0.0),
        )

    cv_mean = float(np.mean(cv_scores))

    # Entrenamiento completo para medir train accuracy
    norm_full = Normalization()
    norm_full.adapt(X)
    layers_full: list[Any] = [norm_full]
    for _ in range(hidden_layers):
        layers_full.append(Dense(neurons, activation="relu"))
        layers_full.append(Dropout(dropout))
    layers_full.append(Dense(output_units, activation=output_activation))
    model_full = Sequential(layers_full)
    model_full.compile(
        optimizer=Adam(learning_rate=lr),
        loss=loss_fn,
        metrics=["accuracy"],
    )
    es_full = EarlyStopping(
        monitor="val_loss", patience=5, restore_best_weights=True, verbose=0
    )
    model_full.fit(
        X, y,
        epochs=epochs, batch_size=batch_size,
        validation_split=0.1,
        callbacks=[es_full],
        class_weight=class_weight_dict,
        verbose=0,
    )
    if is_multiclass:
        y_train_pred = np.argmax(model_full.predict(X, verbose=0), axis=1)
    else:
        y_train_pred = (model_full.predict(X, verbose=0) > 0.5).astype(int).flatten()
    train_acc = float(accuracy_score(y, y_train_pred))
    tf.keras.backend.clear_session()

    f1_cv_result = float(np.mean(cv_f1_scores)) if cv_f1_scores else cv_mean
    win_rate_cv_result = float(np.mean(cv_win_rates)) if cv_win_rates else 0.5
    pf_norm_cv_result = float(np.mean(cv_pf_norms)) if cv_pf_norms else 0.5
    sharpe_norm_cv_result = float(np.mean(cv_sharpe_norms)) if cv_sharpe_norms else 0.5

    return cv_mean, train_acc, f1_cv_result, win_rate_cv_result, pf_norm_cv_result, sharpe_norm_cv_result


# =============================================================================
# FUNCIÓN OBJETIVO DE OPTUNA
# =============================================================================

def _make_objective(
    model_type: str,
    X: np.ndarray,
    y: np.ndarray,
    df_enriched: pd.DataFrame,
    strategy: Any,
    timestamps: np.ndarray,
    symbol: str,
    cv_folds: int,
    trial_log: list[dict[str, Any]],
    label_encoder: Any,
    optuna_max_samples: int = OPTUNA_NN_MAX_SAMPLES,
) -> Any:
    """
    Cierra sobre X, y, df_enriched, strategy, timestamps y devuelve la función
    objetivo multi-métrica para Optuna.

    Puntuación compuesta:
      score = 0.40*F1_cv + 0.30*win_rate_cv + 0.20*pf_norm + 0.10*sharpe_norm
              - penalización_overfitting

    Por cada fold sklearn se corre un backtest real con run_backtest_with_predictions
    para obtener métricas de trading fidedignas a la estrategia.
    """
    suggest_fn = _SUGGEST_FN[model_type]
    is_neural = model_type in ("neural_network", "deep_learning", "keras")
    search_x = X
    search_y = y
    search_timestamps = timestamps
    if model_type == "svm" and len(X) > OPTUNA_SVM_MAX_SAMPLES:
        search_x = X[-OPTUNA_SVM_MAX_SAMPLES:]
        search_y = y[-OPTUNA_SVM_MAX_SAMPLES:]
        search_timestamps = timestamps[-OPTUNA_SVM_MAX_SAMPLES:]
        logger.info(
            "SVM/Optuna limitará dataset de búsqueda: %d → %d filas",
            len(X), len(search_x),
        )
    # TimeSeriesSplit garantiza que la validación siempre es posterior al entrenamiento
    tss = TimeSeriesSplit(n_splits=cv_folds)

    def objective(trial: optuna.Trial) -> float:
        params = suggest_fn(trial)
        trial_params = _prepare_optuna_sklearn_params(model_type, params)
        trial_start = datetime.now()

        logger.info(
            "Trial %3d | %s | iniciando sobre %d filas",
            trial.number,
            model_type,
            len(search_x) if model_type == "svm" else len(X),
        )

        try:
            cv_f1_scores: list[float] = []
            cv_acc_scores: list[float] = []
            cv_win_rates: list[float] = []
            cv_pf_norms: list[float] = []
            cv_sharpe_norms: list[float] = []

            if is_neural:
                (
                    cv_acc, train_acc, f1_cv, win_rate_cv, pf_norm_cv, sharpe_norm_cv
                ) = _build_and_eval_neural_network(
                    X, y, params, cv_folds,
                    df_enriched=df_enriched,
                    strategy=strategy,
                    timestamps=timestamps,
                    label_encoder=label_encoder,
                    symbol=symbol,
                    optuna_max_samples=optuna_max_samples,
                )
            else:
                # Guardamos predicciones del último fold para backtest único al final
                last_y_pred_sk: np.ndarray | None = None
                last_val_idx_sk: np.ndarray | None = None

                for fold_idx, (train_idx, val_idx) in enumerate(tss.split(search_x)):
                    x_tr, x_val = search_x[train_idx], search_x[val_idx]
                    y_tr, y_val = search_y[train_idx], search_y[val_idx]

                    # class_weight para compensar desbalanceo (problema 1)
                    cw_fold = compute_class_weight(
                        "balanced", classes=np.unique(search_y), y=search_y
                    )
                    cw_fold_dict = dict(zip(np.unique(search_y).tolist(), cw_fold.tolist()))
                    model_fold = fit_sklearn_model(
                        model_type,
                        trial_params,
                        x_tr,
                        y_tr,
                        class_weight=cw_fold_dict,
                    )
                    y_pred_val = model_fold.predict(x_val)

                    n_classes_cur = len(np.unique(search_y))
                    avg_mode = "binary" if n_classes_cur <= 2 else "weighted"
                    fold_f1 = float(f1_score(y_val, y_pred_val, average=avg_mode, zero_division=0))
                    fold_acc = float(accuracy_score(y_val, y_pred_val))
                    cv_f1_scores.append(fold_f1)
                    cv_acc_scores.append(fold_acc)
                    last_y_pred_sk = y_pred_val
                    last_val_idx_sk = val_idx

                    logger.debug(
                        "  fold %d/%d | f1=%.4f acc=%.4f",
                        fold_idx + 1, cv_folds, fold_f1, fold_acc,
                    )
                    del x_tr, x_val, y_tr, y_val, model_fold, y_pred_val
                    gc.collect()

                # Backtest UNA VEZ sobre el último fold (datos más recientes)
                if last_y_pred_sk is not None and last_val_idx_sk is not None:
                    y_pred_original = label_encoder.inverse_transform(last_y_pred_sk)
                    val_timestamps = search_timestamps[last_val_idx_sk]
                    df_val = df_enriched[
                        df_enriched["timestamp"].isin(set(val_timestamps.tolist()))
                    ].sort_values("timestamp").reset_index(drop=True)

                    sim = run_backtest_with_predictions(strategy, df_val, y_pred_original, symbol)

                    win_rate_frac = sim["win_rate"] / 100.0
                    pf_norm = min(sim["profit_factor"], 5.0) / 5.0
                    sharpe_norm = max(0.0, min(sim.get("sharpe", 0.0) / 2.0, 1.0))

                    cv_win_rates.append(win_rate_frac)
                    cv_pf_norms.append(pf_norm)
                    cv_sharpe_norms.append(sharpe_norm)
                    logger.debug(
                        "  backtest (último fold) | win_rate=%.2f%% pf=%.4f sharpe=%.4f",
                        sim["win_rate"], sim["profit_factor"], sim.get("sharpe", 0.0),
                    )

                f1_cv = float(np.mean(cv_f1_scores))
                cv_acc = float(np.mean(cv_acc_scores))
                win_rate_cv = float(np.mean(cv_win_rates))
                pf_norm_cv = float(np.mean(cv_pf_norms))
                sharpe_norm_cv = float(np.mean(cv_sharpe_norms))

                model_full = fit_sklearn_model(model_type, trial_params, search_x, search_y)
                train_acc = float(accuracy_score(search_y, model_full.predict(search_x)))

            overfit_gap = train_acc - cv_acc
            composite = _compute_composite_score(
                f1_cv, win_rate_cv, pf_norm_cv, sharpe_norm_cv, overfit_gap
            )

            elapsed = (datetime.now() - trial_start).total_seconds()
            logger.info(
                "Trial %3d | %s | f1_cv=%.4f acc_cv=%.4f win_rate=%.4f pf_norm=%.4f "
                "sharpe_norm=%.4f gap=%.4f → composite=%.4f (%.1fs)",
                trial.number, model_type, f1_cv, cv_acc, win_rate_cv, pf_norm_cv,
                sharpe_norm_cv, overfit_gap, composite, elapsed,
            )

            trial_log.append({
                "trial": trial.number,
                "f1_cv": round(f1_cv, 6),
                "accuracy_cv": round(cv_acc, 6),
                "accuracy_train": round(train_acc, 6),
                "win_rate_cv": round(win_rate_cv, 6),
                "profit_factor_norm_cv": round(pf_norm_cv, 6),
                "sharpe_norm_cv": round(sharpe_norm_cv, 6),
                "overfit_gap": round(overfit_gap, 6),
                "composite_score": round(composite, 6),
                "elapsed_s": round(elapsed, 2),
                "status": "COMPLETE",
                "params": {k: v for k, v in params.items()
                           if k not in ("random_state", "eval_metric",
                                        "use_label_encoder", "verbose")},
            })

            return composite

        except Exception as exc:
            logger.warning("Trial %d falló: %s", trial.number, exc)
            trial_log.append({
                "trial": trial.number,
                "f1_cv": None,
                "accuracy_cv": None,
                "accuracy_train": None,
                "win_rate_cv": None,
                "profit_factor_norm_cv": None,
                "sharpe_norm_cv": None,
                "overfit_gap": None,
                "composite_score": None,
                "elapsed_s": None,
                "status": "FAILED",
                "params": {},
            })
            raise optuna.exceptions.TrialPruned() from exc

    return objective


# =============================================================================
# ENTRENAMIENTO FINAL CON MEJORES PARÁMETROS
# =============================================================================

def _train_neural_final(
    model_type: str,
    best_params: dict[str, Any],
    X: np.ndarray,
    y: np.ndarray,
    X_test: np.ndarray,
    feature_cols: list[str],
    strategy_name: str | None,
    warmup_candles: int | None,
    models_dir: str,
    timeframe: str,
    symbol: str,
    label_encoder: LabelEncoder | None,
) -> tuple[Any, str, np.ndarray]:
    """Entrena y guarda el modelo neural final. Devuelve (model, filename, y_pred_test)."""
    import tensorflow as tf
    from tensorflow.keras.layers import Dense, Dropout, Normalization
    from tensorflow.keras.models import Sequential
    from tensorflow.keras.optimizers import Adam

    gpu_enabled, gpu_names = configure_tensorflow_runtime()
    if gpu_enabled:
        logger.info("TensorFlow entrenamiento final usará GPU: %s", gpu_names)
    else:
        logger.info("TensorFlow entrenamiento final usará CPU")

    epochs = int(best_params.get("epochs", 50))
    batch_size = int(best_params.get("batch_size", 64))
    lr = float(best_params.get("learning_rate", 0.001))
    dropout = float(best_params.get("dropout_rate", 0.3))
    hidden_layers = int(best_params.get("hidden_layers", 2))
    neurons = int(best_params.get("neurons", 64))

    n_cls = len(np.unique(y))
    is_mc = n_cls > 2
    out_units = n_cls if is_mc else 1
    out_act = "softmax" if is_mc else "sigmoid"
    final_loss = "sparse_categorical_crossentropy" if is_mc else "binary_crossentropy"

    norm = Normalization()
    norm.adapt(X)
    layers: list[Any] = [norm]
    for _ in range(hidden_layers):
        layers.append(Dense(neurons, activation="relu"))
        layers.append(Dropout(dropout))
    layers.append(Dense(out_units, activation=out_act))

    model = Sequential(layers)
    model.compile(optimizer=Adam(learning_rate=lr), loss=final_loss, metrics=["accuracy"])
    model.fit(X, y, epochs=epochs, batch_size=batch_size, verbose=0, validation_split=0.1)

    y_pred = (
        np.argmax(model.predict(X_test, verbose=0), axis=1)
        if is_mc
        else (model.predict(X_test, verbose=0) > 0.5).astype(int).flatten()
    )

    suffix = f"_{strategy_name}" if strategy_name else ""
    model_filename = f"{model_type}_{timeframe}_{symbol}{suffix}.keras"
    model_path = os.path.join(models_dir, model_filename)
    model.save(model_path)

    metadata_path = model_path.replace(".keras", ".metadata.json")
    with open(metadata_path, "w", encoding="utf-8") as mf:
        json.dump(
            {
                "feature_cols": feature_cols,
                "strategy_name": strategy_name,
                "warmup_candles": warmup_candles,
                "best_params": best_params,
                "label_classes": label_encoder.classes_.tolist() if label_encoder is not None else None,
            },
            mf,
            indent=2,
        )
    tf.keras.backend.clear_session()
    return model, model_filename, y_pred


def _train_sklearn_final(
    model_type: str,
    best_params: dict[str, Any],
    X: np.ndarray,
    y: np.ndarray,
    X_test: np.ndarray,
    feature_cols: list[str],
    strategy_name: str | None,
    warmup_candles: int | None,
    models_dir: str,
    timeframe: str,
    symbol: str,
    label_encoder: LabelEncoder | None,
) -> tuple[Any, str, np.ndarray]:
    """Entrena y guarda el modelo sklearn final. Devuelve (model, filename, y_pred_test)."""
    fit_params = dict(best_params)
    if model_type == "svm":
        fit_params = _prepare_svm_fit_params(best_params)
        logger.info(
            "Entrenamiento final SVM ajustado: probability=%s max_iter=%s cache_size=%s",
            fit_params.get("probability"),
            fit_params.get("max_iter"),
            fit_params.get("cache_size"),
        )

    model = fit_sklearn_model(model_type, fit_params, X, y)
    y_pred = model.predict(X_test)

    model.feature_cols = feature_cols
    model.strategy_name = strategy_name
    model.warmup_candles = warmup_candles
    model.best_params = best_params
    model.fit_params = fit_params
    model.label_encoder = label_encoder

    suffix = f"_{strategy_name}" if strategy_name else ""
    model_filename = f"{model_type}_{timeframe}_{symbol}{suffix}.pkl"
    model_path = os.path.join(models_dir, model_filename)
    joblib.dump(model, model_path)
    return model, model_filename, y_pred


def _train_final_model(
    model_type: str,
    best_params: dict[str, Any],
    X: np.ndarray,
    y: np.ndarray,
    X_test: np.ndarray,
    y_test: np.ndarray,
    feature_cols: list[str],
    strategy_name: str | None,
    warmup_candles: int | None,
    symbol: str,
    timeframe: str,
    label_encoder: LabelEncoder | None = None,
) -> tuple[Any, str, dict[str, float]]:
    """Entrena el modelo final con mejores hiperparámetros y guarda en disk."""
    models_dir = os.path.join(project_root, "models")
    os.makedirs(models_dir, exist_ok=True)

    is_neural = model_type in ("neural_network", "deep_learning", "keras")
    if is_neural:
        model, model_filename, y_pred = _train_neural_final(
            model_type, best_params, X, y, X_test,
            feature_cols, strategy_name, warmup_candles,
            models_dir, timeframe, symbol, label_encoder,
        )
    else:
        model, model_filename, y_pred = _train_sklearn_final(
            model_type, best_params, X, y, X_test,
            feature_cols, strategy_name, warmup_candles,
            models_dir, timeframe, symbol, label_encoder,
        )

    logger.info("Modelo final guardado en: %s/%s", models_dir, model_filename)

    is_binary = len(np.unique(y_test)) <= 2
    avg = "binary" if is_binary else "weighted"
    metrics = {
        "accuracy": round(float(accuracy_score(y_test, y_pred)), 6),
        "precision": round(float(precision_score(y_test, y_pred, average=avg, zero_division=0)), 6),
        "recall": round(float(recall_score(y_test, y_pred, average=avg, zero_division=0)), 6),
        "f1": round(float(f1_score(y_test, y_pred, average=avg, zero_division=0)), 6),
    }
    return model, model_filename, metrics


# =============================================================================
# GUARDADO DE CSV DE RESULTADOS
# =============================================================================

def _save_results_csv(
    trial_log: list[dict[str, Any]],
    model_type: str,
    timeframe: str,
    symbol: str,
    strategy_name: str | None,
) -> str:
    """Guarda el CSV de todos los trials y devuelve la ruta relativa."""
    results_dir = os.path.join(project_root, "results", RESULTS_SUBDIR)
    os.makedirs(results_dir, exist_ok=True)

    suffix = f"_{strategy_name}" if strategy_name else ""
    csv_filename = f"optimize_{model_type}_{timeframe}_{symbol}{suffix}.csv"
    csv_path = os.path.join(results_dir, csv_filename)

    rows: list[dict[str, Any]] = []
    for entry in trial_log:
        row: dict[str, Any] = {
            "trial": entry["trial"],
            "f1_cv": entry.get("f1_cv"),
            "accuracy_cv": entry.get("accuracy_cv"),
            "accuracy_train": entry.get("accuracy_train"),
            "win_rate_cv": entry.get("win_rate_cv"),
            "profit_factor_norm_cv": entry.get("profit_factor_norm_cv"),
            "sharpe_norm_cv": entry.get("sharpe_norm_cv"),
            "overfit_gap": entry.get("overfit_gap"),
            "composite_score": entry.get("composite_score"),
            "elapsed_s": entry.get("elapsed_s"),
            "status": entry["status"],
        }
        for param_key, param_val in entry.get("params", {}).items():
            row[f"param_{param_key}"] = param_val
        rows.append(row)

    df_results = pd.DataFrame(rows)
    df_results.to_csv(csv_path, index=False)
    logger.info("CSV de resultados guardado en: %s", csv_path)
    return os.path.join("results", RESULTS_SUBDIR, csv_filename)


# =============================================================================
# ENTRY POINT PRINCIPAL
# =============================================================================

def _preparar_dataset(
    payload: dict[str, Any],
    strategy_name: str | None,
    warmup_candles: int | None,
) -> tuple[
    np.ndarray, np.ndarray, np.ndarray, np.ndarray,
    np.ndarray, np.ndarray,
    list[str], LabelEncoder, Any, Any, dict[str, int],
]:
    """Construye X_train, y_train, X_test, y_test y datos auxiliares desde el payload."""
    dataset: list[dict[str, Any]] = payload.get("dataset", [])
    if not dataset:
        raise ValueError("El dataset está vacío.")

    df = pd.DataFrame(dataset)
    df = df.sort_values("timestamp")
    df = df.set_index("timestamp")

    if not strategy_name:
        raise ValueError(
            "strategy_name es obligatorio: los indicadores deben calcularse siempre "
            "a través de populate_indicators() de la estrategia. "
            "Especifica una estrategia válida con --strategy."
        )

    logger.info("Modo dinámico activado con estrategia: %s", strategy_name)
    strategy = load_strategy(strategy_name)

    df_raw = df.reset_index() if df.index.name == "timestamp" else df.copy()
    if "timestamp" not in df_raw.columns:
        df_raw["timestamp"] = df_raw.index

    # ── Split ANTES de calcular indicadores ──────────────────────────────────────
    # Los indicadores backward-looking (EMA, RSI, Bollinger…) son seguros calculados
    # sobre el dataset completo, pero separar aquí garantiza rigor metodológico y
    # previene leakage ante cualquier indicador con componente global en el futuro.
    raw_split_idx = int(len(df_raw) * 0.8)
    lookback_buffer = strategy.get_warmup_period()
    buffer_start = max(0, raw_split_idx - lookback_buffer)

    df_train_raw = df_raw.iloc[:raw_split_idx].reset_index(drop=True)
    df_test_buffer_raw = df_raw.iloc[buffer_start:].reset_index(drop=True)
    logger.info(
        "Split previo a indicadores: train=%d filas, test+buffer=%d filas (buffer=%d velas)",
        len(df_train_raw), len(df_test_buffer_raw), raw_split_idx - buffer_start,
    )

    logger.info("Calculando indicadores — conjunto TRAIN...")
    t0 = datetime.now()
    df_train_enriched = strategy.populate_indicators(df_train_raw.copy())
    logger.info("populate_indicators TRAIN: %.2fs (%d filas)",
                (datetime.now() - t0).total_seconds(), len(df_train_enriched))

    logger.info("Calculando indicadores — conjunto TEST (con buffer de calentamiento)...")
    t1 = datetime.now()
    df_test_enriched_buf = strategy.populate_indicators(df_test_buffer_raw.copy())
    buffer_rows = raw_split_idx - buffer_start
    df_test_enriched = df_test_enriched_buf.iloc[buffer_rows:].reset_index(drop=True)
    logger.info("populate_indicators TEST: %.2fs (%d filas)",
                (datetime.now() - t1).total_seconds(), len(df_test_enriched))

    del df_train_raw, df_test_buffer_raw, df_test_enriched_buf

    x_df_train, y_ser_train = apply_strategy_features(
        df_train_enriched.copy(), strategy, int(warmup_candles or 0),
        already_enriched=True,
    )
    x_df_test, y_ser_test = apply_strategy_features(
        df_test_enriched.copy(), strategy, 0,
        already_enriched=True,
    )

    feature_cols = list(x_df_train.columns)
    x_train_full = x_df_train.values.astype(float)
    y_train_full_raw = y_ser_train.values
    X_test = x_df_test.values.astype(float)
    y_test_raw = y_ser_test.values
    timestamps_train = x_df_train.index.values.astype(np.int64)
    timestamps_test = x_df_test.index.values.astype(np.int64)

    label_distribution = {
        str(k): int(v)
        for k, v in y_ser_train.value_counts(dropna=False).to_dict().items()
    }
    logger.info(
        "Dataset listo: train=%d filas, test=%d filas, features=%d, distribución labels=%s",
        len(x_train_full), len(X_test), len(feature_cols), label_distribution,
    )

    # df_enriched_full para backtests finales (train + test enriquecidos)
    df_enriched_full = pd.concat(
        [df_train_enriched, df_test_enriched], ignore_index=True
    ).sort_values("timestamp").reset_index(drop=True)

    label_encoder = LabelEncoder()
    y_train_full = label_encoder.fit_transform(y_train_full_raw)
    y_test = label_encoder.transform(y_test_raw)
    logger.info(
        "Labels codificados: %s → índices 0..%d",
        label_encoder.classes_.tolist(),
        len(label_encoder.classes_) - 1,
    )

    del df, df_raw, x_df_train, x_df_test, y_ser_train, y_ser_test
    gc.collect()

    return (
        x_train_full, y_train_full,
        X_test, y_test,
        timestamps_train, timestamps_test,
        feature_cols, label_encoder,
        df_enriched_full, strategy,
        label_distribution,
    )


def _construir_payload_respuesta(
    status: str,
    model_filename: str,
    model_type_used: str,
    best_params: dict[str, Any],
    best_composite: float,
    best_f1_cv: float,
    best_cv_acc: float,
    best_train_acc: float,
    best_win_rate_cv: float,
    best_overfit_gap: float,
    min_composite: float,
    min_composite_reached: bool,
    trials_completed: int,
    n_trials: int,
    feature_cols: list[str],
    strategy_name: str | None,
    warmup_candles: int | None,
    label_distribution: dict[str, int],
    label_encoder: LabelEncoder,
    csv_rel_path: str,
    final_metrics: dict[str, float],
    test_stats: dict[str, Any],
    x_np: np.ndarray,
    x_train_full: np.ndarray,
    X_test: np.ndarray,
    cv_folds: int,
    model_type: str,
    trial_log: list[dict[str, Any]],
) -> dict[str, Any]:
    """Construye el payload de respuesta IPC OPTIMIZE_RESPONSE."""
    serializable_best_params = {
        k: (v if not isinstance(v, float) or not (math.isnan(v) or math.isinf(v)) else str(v))
        for k, v in best_params.items()
    }
    return {
        "status": status,
        "model_saved_at": model_filename,
        "model_type_used": model_type_used,
        "best_params": serializable_best_params,
        "best_composite_score": round(best_composite, 6),
        "best_f1_cv": round(best_f1_cv, 6),
        "best_accuracy_cv": round(best_cv_acc, 6),
        "best_accuracy_cv_pct": round(best_cv_acc * 100, 2),
        "best_accuracy_train": round(best_train_acc, 6),
        "best_win_rate_cv": round(best_win_rate_cv, 6),
        "overfit_gap": round(best_overfit_gap, 6),
        "min_composite_required": min_composite,
        "min_composite_reached": min_composite_reached,
        "min_accuracy_required": min_composite,
        "min_accuracy_reached": min_composite_reached,
        "trials_completed": trials_completed,
        "trials_total": n_trials,
        "feature_cols": feature_cols,
        "strategy_name": strategy_name,
        "warmup_candles": warmup_candles,
        "label_distribution": label_distribution,
        "label_classes": label_encoder.classes_.tolist(),
        "csv_path": csv_rel_path,
        "final_metrics": {
            "accuracy": round(final_metrics["accuracy"] * 100, 2),
            "precision": round(final_metrics["precision"] * 100, 2),
            "recall": round(final_metrics["recall"] * 100, 2),
            "f1": round(final_metrics["f1"] * 100, 2),
        },
        "trading_simulation_test": {
            "op_totales": test_stats["op_totales"],
            "op_ganadas": test_stats["op_ganadas"],
            "op_perdidas": test_stats["op_perdidas"],
            "win_rate": round(test_stats["win_rate"], 2),
            "profit_factor": round(test_stats["profit_factor"], 4),
            "sharpe": round(test_stats.get("sharpe", 0.0), 4),
            "retorno_acumulado": round(test_stats["retorno_acumulado"], 4),
            "retorno_total": round(test_stats["retorno_total"], 2),
            "max_drawdown": round(test_stats["max_drawdown"], 4),
        },
        "all_trials": trial_log,
        "data_info": {
            "modelo_usado": model_type.upper(),
            "total_filas": len(x_np),
            "filas_entrenamiento": len(x_train_full),
            "filas_test": len(X_test),
            "num_features": len(feature_cols),
            "cv_folds": cv_folds,
            "n_trials_solicitados": n_trials,
            "n_trials_completados": trials_completed,
            "objetivo_pesos": {
                "F1_cv": 0.25,
                "win_rate": 0.40,
                "profit_factor": 0.25,
                "sharpe": 0.10,
            },
        },
    }


# =============================================================================
# ENTRY POINT PRINCIPAL
# =============================================================================

def main() -> None:
    logger.info("=== Iniciando engine_optimize.py — Búsqueda de Hiperparámetros Óptimos ===")

    try:
        payload = read_request_payload()

        model_type = str(payload.get("model_type", "random_forest")).lower().strip()
        symbol = str(payload.get("symbol", "UNKNOWN"))
        timeframe = str(payload.get("timeframe", "UNKNOWN"))
        min_composite: float = float(
            payload.get("min_composite", payload.get("min_accuracy", DEFAULT_MIN_COMPOSITE))
        )
        n_trials: int = int(payload.get("n_trials", DEFAULT_N_TRIALS))
        cv_folds: int = int(payload.get("cv_folds", DEFAULT_CV_FOLDS))
        strategy_name: str | None = payload.get("strategy_name") or None
        warmup_candles: int | None = payload.get("warmup_candles")

        logger.info(
            "Parámetros: model=%s symbol=%s tf=%s n_trials=%d cv_folds=%d "
            "min_composite=%.2f strategy=%s",
            model_type, symbol, timeframe, n_trials, cv_folds,
            min_composite, strategy_name or "None (indicadores)",
        )

        (
            x_train_full, y_train_full,
            X_test, y_test,
            timestamps_train, timestamps_test,
            feature_cols, label_encoder,
            df_enriched_full, strategy,
            label_distribution,
        ) = _preparar_dataset(payload, strategy_name, warmup_candles)

        x_np = np.concatenate([x_train_full, X_test])

        if _SUGGEST_FN.get(model_type) is None:
            raise ValueError(
                f"Tipo de modelo no reconocido: '{model_type}'. "
                f"Válidos: {sorted(_SUGGEST_FN.keys())}"
            )

        logger.info(
            "Iniciando Optuna study: %d trials, pruner=MedianPruner, cv=%d folds",
            n_trials, cv_folds,
        )
        logger.info(
            "Fórmula objetivo compuesta: F1_cv*0.25 + WinRate*0.40 + PF_norm*0.25 + Sharpe_norm*0.10 - penalización_overfitting",
        )

        trial_log: list[dict[str, Any]] = []
        study_name = build_study_name(model_type, symbol, timeframe)
        storage = resolve_optuna_storage()
        pruner = MedianPruner(n_startup_trials=5, n_warmup_steps=10)

        try:
            study = optuna.create_study(
                direction="maximize",
                pruner=pruner,
                study_name=study_name,
                storage=storage,
            )
            if storage:
                logger.info("Estudio '%s' persistido en %s", study_name, storage)
        except Exception as exc:  # noqa: BLE001 - degradar a memoria antes que abortar
            logger.warning(
                "Almacén Optuna no disponible (%s); el estudio correrá solo en memoria", exc,
            )
            study = optuna.create_study(
                direction="maximize",
                pruner=pruner,
                study_name=study_name,
            )
        study.optimize(
            _make_objective(
                model_type, x_train_full, y_train_full,
                df_enriched_full, strategy, timestamps_train,
                symbol, cv_folds, trial_log, label_encoder,
                optuna_max_samples=OPTUNA_NN_MAX_SAMPLES,
            ),
            n_trials=n_trials,
            show_progress_bar=False,
            gc_after_trial=True,
        )

        completed_trials = [t for t in study.trials if t.state == optuna.trial.TrialState.COMPLETE]
        if not completed_trials:
            raise ValueError("Ningún trial completó correctamente. Revisa los logs para más detalles.")

        best_trial = study.best_trial
        best_params = best_trial.params
        best_log_entry = next((e for e in trial_log if e["trial"] == best_trial.number), {})
        best_cv_acc = float(best_log_entry.get("accuracy_cv") or 0.0)
        best_f1_cv = float(best_log_entry.get("f1_cv") or 0.0)
        best_train_acc = float(best_log_entry.get("accuracy_train") or 0.0)
        best_overfit_gap = float(best_log_entry.get("overfit_gap") or 0.0)
        best_win_rate_cv = float(best_log_entry.get("win_rate_cv") or 0.0)
        best_composite = float(best_log_entry.get("composite_score") or 0.0)
        min_composite_reached = best_composite >= min_composite
        status = "success" if min_composite_reached else "warning"
        trials_completed = len(completed_trials)

        logger.info(
            "=== MEJOR TRIAL #%d === composite=%.4f | f1_cv=%.4f acc_cv=%.4f (%.2f%%) "
            "train_acc=%.4f gap=%.4f win_rate_cv=%.4f | min_composite_reached=%s",
            best_trial.number, best_composite, best_f1_cv,
            best_cv_acc, best_cv_acc * 100,
            best_train_acc, best_overfit_gap, best_win_rate_cv,
            min_composite_reached,
        )
        if not min_composite_reached:
            logger.warning(
                "No se alcanzó el mínimo de composite requerido (%.4f). "
                "Mejor obtenido: %.4f. Se guarda igualmente el mejor modelo encontrado.",
                min_composite, best_composite,
            )

        logger.info("Entrenando modelo final con mejores hiperparámetros...")
        final_model, model_filename, final_metrics = _train_final_model(
            model_type=model_type,
            best_params=best_params,
            X=x_train_full,
            y=y_train_full,
            X_test=X_test,
            y_test=y_test,
            feature_cols=feature_cols,
            strategy_name=strategy_name,
            warmup_candles=warmup_candles,
            symbol=symbol,
            timeframe=timeframe,
            label_encoder=label_encoder,
        )

        logger.info("Ejecutando backtest real sobre el conjunto de TEST (20%%)...")
        is_neural_final = model_type in ("neural_network", "deep_learning", "keras")
        if is_neural_final:
            import tensorflow as _tf_final  # noqa: F401
            y_test_pred_final = (final_model.predict(X_test, verbose=0) > 0.5).astype(int).flatten()
        else:
            y_test_pred_final = final_model.predict(X_test)

        y_test_pred_original = label_encoder.inverse_transform(y_test_pred_final)
        df_test = df_enriched_full[
            df_enriched_full["timestamp"].isin(set(timestamps_test.tolist()))
        ].sort_values("timestamp").reset_index(drop=True)
        test_stats = run_backtest_with_predictions(strategy, df_test, y_test_pred_original, symbol)
        del df_enriched_full, df_test
        gc.collect()

        logger.info(
            "Backtest test: op_totales=%d win_rate=%.2f%% profit_factor=%.4f "
            "sharpe=%.4f retorno_acumulado=%.2f%%",
            test_stats["op_totales"], test_stats["win_rate"], test_stats["profit_factor"],
            test_stats.get("sharpe", 0.0), test_stats["retorno_acumulado"],
        )

        csv_rel_path = _save_results_csv(trial_log, model_type, timeframe, symbol, strategy_name)

        resultado = _construir_payload_respuesta(
            status=status,
            model_filename=model_filename,
            model_type_used=model_type,
            best_params=best_params,
            best_composite=best_composite,
            best_f1_cv=best_f1_cv,
            best_cv_acc=best_cv_acc,
            best_train_acc=best_train_acc,
            best_win_rate_cv=best_win_rate_cv,
            best_overfit_gap=best_overfit_gap,
            min_composite=min_composite,
            min_composite_reached=min_composite_reached,
            trials_completed=trials_completed,
            n_trials=n_trials,
            feature_cols=feature_cols,
            strategy_name=strategy_name,
            warmup_candles=warmup_candles,
            label_distribution=label_distribution,
            label_encoder=label_encoder,
            csv_rel_path=csv_rel_path,
            final_metrics=final_metrics,
            test_stats=test_stats,
            x_np=x_np,
            x_train_full=x_train_full,
            X_test=X_test,
            cv_folds=cv_folds,
            model_type=model_type,
            trial_log=trial_log,
        )

        write_response("OPTIMIZE_RESPONSE", resultado)
        logger.info("=== engine_optimize.py finalizado con éxito ===")

    except Exception as exc:
        logger.error("Fallo durante la optimización: %s", str(exc), exc_info=True)
        write_error("ERROR", str(exc))
        sys.exit(1)


if __name__ == "__main__":
    main()
