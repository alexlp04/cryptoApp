"""
engine_optimize.py — Motor de búsqueda de hiperparámetros óptimos con Optuna.

Protocolo IPC:
  Entrada : MessagePack framed (4 bytes big-endian + envelope) con type OPTIMIZE_REQUEST
  Salida  : MessagePack framed (4 bytes big-endian + envelope) con type OPTIMIZE_RESPONSE

Payload de entrada (desde AIOptimizationService.java):
  {
    "model_type":    str     — xgboost | lightgbm | random_forest | neural_network
    "symbol":        str     — ej. "BTCUSDT"
    "timeframe":     str     — ej. "1h"
    "dataset":       list    — lista de rows {timestamp, open?, high?, low?, close, volume, indics…}
    "min_accuracy":  float   — mínimo de accuracy en CV para considerar éxito (fracción, ej. 0.60)
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
import importlib
import json
import logging
import os
import sys
import warnings
from datetime import datetime
from typing import Any

import joblib
import numpy as np
import optuna
import pandas as pd
from optuna.pruners import MedianPruner
from sklearn.ensemble import RandomForestClassifier
from sklearn.metrics import accuracy_score, f1_score, precision_score, recall_score
from sklearn.model_selection import StratifiedKFold
from sklearn.preprocessing import LabelEncoder
import lightgbm as lgb
import xgboost as xgb

from ipc_protocol import read_request_payload, write_error, write_response
from backtest_engine import run_backtest_with_predictions
from shared_utils import load_strategy_by_name, apply_strategy_features

warnings.filterwarnings("ignore", category=UserWarning)
optuna.logging.set_verbosity(optuna.logging.WARNING)

# =============================================================================
# CONFIGURACIÓN DE RUTAS Y LOGS
# =============================================================================
current_dir = os.path.dirname(os.path.abspath(__file__))
code_dir = os.path.dirname(current_dir)
project_root = os.path.dirname(code_dir)

log_dir = os.path.join(project_root, "logs")
os.makedirs(log_dir, exist_ok=True)

log_file = os.path.join(log_dir, "engine_optimize.log")
_file_handler = logging.FileHandler(log_file, encoding="utf-8")
_file_handler.setLevel(logging.INFO)
_file_handler.setFormatter(logging.Formatter("%(asctime)s [%(levelname)s] %(message)s"))

_stderr_handler = logging.StreamHandler(sys.stderr)
_stderr_handler.setLevel(logging.INFO)
_stderr_handler.setFormatter(logging.Formatter("[%(levelname)s] %(message)s"))

logging.basicConfig(
    level=logging.INFO,
    handlers=[_file_handler, _stderr_handler],
)

logger = logging.getLogger(__name__)

# =============================================================================
# CONSTANTES
# =============================================================================
DEFAULT_N_TRIALS: int = 100
DEFAULT_CV_FOLDS: int = 5
DEFAULT_MIN_ACCURACY: float = 0.55
MIN_SAMPLES_REQUIRED: int = 50
RESULTS_SUBDIR: str = "optimize"


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
        0.40 * f1_cv
        + 0.30 * win_rate_frac
        + 0.20 * pf_norm
        + 0.10 * sharpe_norm
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
    "neural_network": _suggest_neural_network,
    "deep_learning": _suggest_neural_network,
    "keras": _suggest_neural_network,
}


# =============================================================================
# CONSTRUCCIÓN DE MODELO POR TIPO
# =============================================================================

def _build_sklearn_model(model_type: str, params: dict[str, Any]) -> Any:
    """Instancia el modelo sklearn/xgb/lgb con los parámetros dados."""
    clean = {k: v for k, v in params.items() if k not in ("epochs", "batch_size",
                                                            "hidden_layers", "neurons")}
    if model_type == "xgboost":
        # Eliminar clave obsoleta si Optuna la incluyó
        clean.pop("use_label_encoder", None)
        return xgb.XGBClassifier(**clean)
    if model_type == "lightgbm":
        return lgb.LGBMClassifier(**clean)
    # random_forest y fallback
    rf_keys = {"n_estimators", "max_depth", "min_samples_split",
               "min_samples_leaf", "max_features", "random_state"}
    rf_params = {k: v for k, v in clean.items() if k in rf_keys}
    return RandomForestClassifier(**rf_params)


def _build_and_eval_neural_network(
    X: np.ndarray, y: np.ndarray, params: dict[str, Any], cv_folds: int
) -> tuple[float, float]:
    """
    Entrena una red neuronal con los parámetros dados y devuelve
    (cv_accuracy_mean, train_accuracy).
    No usa cross_val_score (Keras no es compatible) → hace k-fold manual.
    """
    import tensorflow as tf  # importación diferida para evitar overhead cuando no se usa
    from tensorflow.keras.layers import Dense, Dropout, Normalization
    from tensorflow.keras.models import Sequential
    from tensorflow.keras.optimizers import Adam

    epochs = int(params.get("epochs", 50))
    batch_size = int(params.get("batch_size", 64))
    lr = float(params.get("learning_rate", 0.001))
    dropout = float(params.get("dropout_rate", 0.3))
    hidden_layers = int(params.get("hidden_layers", 2))
    neurons = int(params.get("neurons", 64))

    n_classes_nn = len(np.unique(y))
    is_multiclass = n_classes_nn > 2
    output_units = n_classes_nn if is_multiclass else 1
    output_activation = "softmax" if is_multiclass else "sigmoid"
    loss_fn = "sparse_categorical_crossentropy" if is_multiclass else "binary_crossentropy"

    skf = StratifiedKFold(n_splits=cv_folds, shuffle=False)
    cv_scores: list[float] = []

    for fold_idx, (train_idx, val_idx) in enumerate(skf.split(X, y)):
        X_tr, X_val = X[train_idx], X[val_idx]
        y_tr, y_val = y[train_idx], y[val_idx]

        norm = Normalization()
        norm.adapt(X_tr)

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
        model.fit(X_tr, y_tr, epochs=epochs, batch_size=batch_size,
                  verbose=0, validation_split=0.0)
        if is_multiclass:
            y_pred_fold = np.argmax(model.predict(X_val, verbose=0), axis=1)
        else:
            y_pred_fold = (model.predict(X_val, verbose=0) > 0.5).astype(int).flatten()
        cv_scores.append(float(accuracy_score(y_val, y_pred_fold)))
        tf.keras.backend.clear_session()
        logger.debug("NN fold %d/%d — val_acc=%.4f", fold_idx + 1, cv_folds, cv_scores[-1])

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
    model_full.fit(X, y, epochs=epochs, batch_size=batch_size, verbose=0)
    if is_multiclass:
        y_train_pred = np.argmax(model_full.predict(X, verbose=0), axis=1)
    else:
        y_train_pred = (model_full.predict(X, verbose=0) > 0.5).astype(int).flatten()
    train_acc = float(accuracy_score(y, y_train_pred))
    tf.keras.backend.clear_session()

    return cv_mean, train_acc


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
    suggest_fn = _SUGGEST_FN.get(model_type, _suggest_random_forest)
    is_neural = model_type in ("neural_network", "deep_learning", "keras")
    skf = StratifiedKFold(n_splits=cv_folds, shuffle=False)

    def objective(trial: optuna.Trial) -> float:
        params = suggest_fn(trial)
        trial_start = datetime.now()

        try:
            cv_f1_scores: list[float] = []
            cv_acc_scores: list[float] = []
            cv_win_rates: list[float] = []
            cv_pf_norms: list[float] = []
            cv_sharpe_norms: list[float] = []

            if is_neural:
                cv_acc, train_acc = _build_and_eval_neural_network(X, y, params, cv_folds)
                f1_cv = cv_acc
                win_rate_cv = cv_acc
                pf_norm_cv = cv_acc
                sharpe_norm_cv = cv_acc
            else:
                for fold_idx, (train_idx, val_idx) in enumerate(skf.split(X, y)):
                    X_tr, X_val = X[train_idx], X[val_idx]
                    y_tr, y_val = y[train_idx], y[val_idx]

                    model_fold = _build_sklearn_model(model_type, params)
                    model_fold.fit(X_tr, y_tr)
                    y_pred_val = model_fold.predict(X_val)

                    n_classes_cur = len(np.unique(y))
                    avg_mode = "binary" if n_classes_cur <= 2 else "weighted"
                    fold_f1 = float(f1_score(y_val, y_pred_val, average=avg_mode, zero_division=0))
                    fold_acc = float(accuracy_score(y_val, y_pred_val))
                    cv_f1_scores.append(fold_f1)
                    cv_acc_scores.append(fold_acc)

                    # Backtest real sobre el fold de validación con predicciones inversas
                    y_pred_original = label_encoder.inverse_transform(y_pred_val)
                    val_timestamps = timestamps[val_idx]
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
                        "  fold %d/%d | f1=%.4f acc=%.4f win_rate=%.2f%% pf=%.4f sharpe=%.4f",
                        fold_idx + 1, cv_folds, fold_f1, fold_acc,
                        sim["win_rate"], sim["profit_factor"], sim.get("sharpe", 0.0),
                    )

                f1_cv = float(np.mean(cv_f1_scores))
                cv_acc = float(np.mean(cv_acc_scores))
                win_rate_cv = float(np.mean(cv_win_rates))
                pf_norm_cv = float(np.mean(cv_pf_norms))
                sharpe_norm_cv = float(np.mean(cv_sharpe_norms))

                model_full = _build_sklearn_model(model_type, params)
                model_full.fit(X, y)
                train_acc = float(accuracy_score(y, model_full.predict(X)))

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
        import tensorflow as tf
        from tensorflow.keras.layers import Dense, Dropout, Normalization
        from tensorflow.keras.models import Sequential
        from tensorflow.keras.optimizers import Adam

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
        model.compile(
            optimizer=Adam(learning_rate=lr),
            loss=final_loss,
            metrics=["accuracy"],
        )
        model.fit(X, y, epochs=epochs, batch_size=batch_size,
                  verbose=0, validation_split=0.1)

        if is_mc:
            y_pred = np.argmax(model.predict(X_test, verbose=0), axis=1)
        else:
            y_pred = (model.predict(X_test, verbose=0) > 0.5).astype(int).flatten()

        suffix = f"_{strategy_name}" if strategy_name else ""
        model_filename = f"{model_type}_{timeframe}_{symbol}{suffix}.keras"
        model_path = os.path.join(models_dir, model_filename)
        model.save(model_path)

        # Metadatos separados para NN (igual que engine_train.py)
        metadata_path = model_path.replace(".keras", ".metadata.json")
        with open(metadata_path, "w", encoding="utf-8") as mf:
            json.dump(
                {"feature_cols": feature_cols,
                 "strategy_name": strategy_name,
                 "warmup_candles": warmup_candles,
                 "best_params": best_params,
                 "label_classes": label_encoder.classes_.tolist() if label_encoder is not None else None},
                mf,
                indent=2,
            )
        tf.keras.backend.clear_session()

    else:
        model = _build_sklearn_model(model_type, best_params)
        model.fit(X, y)
        y_pred = model.predict(X_test)

        # Adjuntamos metadatos como atributos (igual que engine_train.py)
        setattr(model, "feature_cols", feature_cols)
        setattr(model, "strategy_name", strategy_name)
        setattr(model, "warmup_candles", warmup_candles)
        setattr(model, "best_params", best_params)
        setattr(model, "label_encoder", label_encoder)

        suffix = f"_{strategy_name}" if strategy_name else ""
        model_filename = f"{model_type}_{timeframe}_{symbol}{suffix}.pkl"
        model_path = os.path.join(models_dir, model_filename)
        joblib.dump(model, model_path)

    logger.info("Modelo final guardado en: %s", model_path)

    # Métricas finales
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

def main() -> None:
    logger.info("=== Iniciando engine_optimize.py — Búsqueda de Hiperparámetros Óptimos ===")

    try:
        payload = read_request_payload()

        # ── Extraer parámetros del payload ──────────────────────────────────
        model_type = str(payload.get("model_type", "random_forest")).lower().strip()
        symbol = str(payload.get("symbol", "UNKNOWN"))
        timeframe = str(payload.get("timeframe", "UNKNOWN"))
        dataset: list[dict[str, Any]] = payload.get("dataset", [])
        min_accuracy: float = float(payload.get("min_accuracy", DEFAULT_MIN_ACCURACY))
        n_trials: int = int(payload.get("n_trials", DEFAULT_N_TRIALS))
        cv_folds: int = int(payload.get("cv_folds", DEFAULT_CV_FOLDS))
        strategy_name: str | None = payload.get("strategy_name") or None
        warmup_candles: int | None = payload.get("warmup_candles")

        logger.info(
            "Parámetros: model=%s symbol=%s tf=%s n_trials=%d cv_folds=%d "
            "min_accuracy=%.2f strategy=%s",
            model_type, symbol, timeframe, n_trials, cv_folds,
            min_accuracy, strategy_name or "None (indicadores)",
        )

        if not dataset:
            raise ValueError("El dataset está vacío.")

        # ── Preparación del DataFrame ────────────────────────────────────────
        df = pd.DataFrame(dataset)
        df.sort_values("timestamp", inplace=True)
        df.set_index("timestamp", inplace=True)

        feature_cols: list[str]

        if strategy_name:
            logger.info("Modo dinámico activado con estrategia: %s", strategy_name)
            strategy = load_strategy(strategy_name)

            # 1. Enriquecer el DataFrame completo con los indicadores de la estrategia
            df_raw = df.reset_index() if df.index.name == "timestamp" else df.copy()
            if "timestamp" not in df_raw.columns:
                df_raw["timestamp"] = df_raw.index

            logger.info("Iniciando populate_indicators para estrategia '%s'", strategy_name)
            t0 = datetime.now()
            df_enriched_full = strategy.populate_indicators(df_raw.copy())
            logger.info(
                "populate_indicators finalizado en %.2fs (filas=%d)",
                (datetime.now() - t0).total_seconds(),
                len(df_enriched_full),
            )

            # 2. Extraer features/labels (indica df ya enriquecido con indicadores)
            X_df, y_ser = apply_strategy_features(
                df_enriched_full.copy(), strategy, int(warmup_candles or 0),
                already_enriched=True,
            )
            feature_cols = list(X_df.columns)
        else:
            raise ValueError(
                "strategy_name es obligatorio: los indicadores deben calcularse siempre "
                "a través de populate_indicators() de la estrategia. "
                "Especifica una estrategia válida con --strategy."
            )

        X_np = X_df.values.astype(float)
        y_np = y_ser.values

        # Timestamps reales (epoch ms) alineados con X/y — usados para alinear folds y test sim
        timestamps_all = X_df.index.values.astype(np.int64)

        label_distribution = {
            str(k): int(v)
            for k, v in y_ser.value_counts(dropna=False).to_dict().items()
        }
        logger.info(
            "Dataset listo: %d filas, %d features, distribución labels=%s",
            len(X_np), len(feature_cols), label_distribution,
        )

        # ── División train/test (80/20) ──────────────────────────────────────
        split_idx = int(len(X_np) * 0.8)
        X_train_full = X_np[:split_idx]
        y_train_full = y_np[:split_idx]
        X_test = X_np[split_idx:]
        y_test = y_np[split_idx:]
        timestamps_train = timestamps_all[:split_idx]
        timestamps_test = timestamps_all[split_idx:]

        # ── Codificación de labels (XGBoost requiere clases 0-indexadas) ─────
        # Los labels de estrategia son {-1, 0, 1} → se recodifican a {0, 1, 2}
        label_encoder = LabelEncoder()
        y_train_full = label_encoder.fit_transform(y_train_full)
        y_test = label_encoder.transform(y_test)
        logger.info(
            "Labels codificados: %s → índices 0..%d",
            label_encoder.classes_.tolist(),
            len(label_encoder.classes_) - 1,
        )

        # Liberación de memoria — df_enriched_full se mantiene para folds y test sim
        del df, X_df, y_ser, timestamps_all
        if strategy_name and "prices_aligned" in dir():
            del prices_aligned
        gc.collect()

        # ── Optuna study ────────────────────────────────────────────────────
        trial_log: list[dict[str, Any]] = []
        suggest_fn = _SUGGEST_FN.get(model_type)
        if suggest_fn is None:
            logger.warning(
                "Tipo de modelo '%s' no reconocido. Usando random_forest como fallback.",
                model_type,
            )
            model_type = "random_forest"

        logger.info(
            "Iniciando Optuna study: %d trials, pruner=MedianPruner, cv=%d folds",
            n_trials, cv_folds,
        )
        logger.info(
            "Fórmula objetivo compuesta: F1_cv*0.40 + WinRate*0.30 + PF_norm*0.20 + Sharpe_norm*0.10 - penalización_overfitting",
        )

        study = optuna.create_study(
            direction="maximize",
            pruner=MedianPruner(n_startup_trials=5, n_warmup_steps=10),
            study_name=f"optimize_{model_type}_{symbol}_{timeframe}",
        )

        objective_fn = _make_objective(
            model_type, X_train_full, y_train_full,
            df_enriched_full, strategy, timestamps_train,
            symbol, cv_folds, trial_log, label_encoder,
        )

        study.optimize(
            objective_fn,
            n_trials=n_trials,
            show_progress_bar=False,
            gc_after_trial=True,
        )

        # ── Resultados del study ─────────────────────────────────────────────
        completed_trials = [t for t in study.trials
                            if t.state == optuna.trial.TrialState.COMPLETE]
        trials_completed = len(completed_trials)

        if not completed_trials:
            raise ValueError(
                "Ningún trial completó correctamente. Revisa los logs para más detalles."
            )

        best_trial = study.best_trial
        best_params = best_trial.params

        # Reconstituimos métricas del mejor trial desde el log
        best_log_entry = next(
            (e for e in trial_log if e["trial"] == best_trial.number), {}
        )
        best_cv_acc: float = float(best_log_entry.get("accuracy_cv") or 0.0)
        best_f1_cv: float = float(best_log_entry.get("f1_cv") or 0.0)
        best_train_acc: float = float(best_log_entry.get("accuracy_train") or 0.0)
        best_overfit_gap: float = float(best_log_entry.get("overfit_gap") or 0.0)
        best_win_rate_cv: float = float(best_log_entry.get("win_rate_cv") or 0.0)
        best_composite: float = float(best_log_entry.get("composite_score") or 0.0)

        min_accuracy_reached = best_cv_acc >= min_accuracy
        status = "success" if min_accuracy_reached else "warning"

        logger.info(
            "=== MEJOR TRIAL #%d === composite=%.4f | f1_cv=%.4f acc_cv=%.4f (%.2f%%) "
            "train_acc=%.4f gap=%.4f win_rate_cv=%.4f | min_accuracy_reached=%s",
            best_trial.number, best_composite, best_f1_cv,
            best_cv_acc, best_cv_acc * 100,
            best_train_acc, best_overfit_gap, best_win_rate_cv,
            min_accuracy_reached,
        )
        if not min_accuracy_reached:
            logger.warning(
                "No se alcanzó el mínimo de accuracy requerido (%.2f%%). "
                "Mejor obtenido: %.2f%%. Se guarda igualmente el mejor modelo encontrado.",
                min_accuracy * 100, best_cv_acc * 100,
            )

        # ── Entrenamiento final con mejores hiperparámetros ──────────────────
        logger.info("Entrenando modelo final con mejores hiperparámetros...")
        final_model, model_filename, final_metrics = _train_final_model(
            model_type=model_type,
            best_params=best_params,
            X=X_train_full,
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

        # ── Backtest real sobre el conjunto de TEST con predicciones del modelo ─
        logger.info("Ejecutando backtest real sobre el conjunto de TEST (20%%)...")
        is_neural_final = model_type in ("neural_network", "deep_learning", "keras")
        if is_neural_final:
            import tensorflow as _tf_final  # noqa: F401 — importación diferida
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

        # ── CSV de resultados de todos los trials ─────────────────────────────
        csv_rel_path = _save_results_csv(
            trial_log, model_type, timeframe, symbol, strategy_name
        )

        # ── Payload de respuesta ─────────────────────────────────────────────
        # Filtrar best_params para serialización segura (quitar None, etc.)
        serializable_best_params = {
            k: (v if not isinstance(v, float) or not (
                float("nan") == v or float("inf") == abs(v)
            ) else str(v))
            for k, v in best_params.items()
        }

        resultado: dict[str, Any] = {
            "status": status,
            "model_saved_at": model_filename,
            "best_params": serializable_best_params,
            "best_composite_score": round(best_composite, 6),
            "best_f1_cv": round(best_f1_cv, 6),
            "best_accuracy_cv": round(best_cv_acc, 6),
            "best_accuracy_cv_pct": round(best_cv_acc * 100, 2),
            "best_accuracy_train": round(best_train_acc, 6),
            "best_win_rate_cv": round(best_win_rate_cv, 6),
            "overfit_gap": round(best_overfit_gap, 6),
            "min_accuracy_required": min_accuracy,
            "min_accuracy_reached": min_accuracy_reached,
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
                "total_filas": len(X_np),
                "filas_entrenamiento": len(X_train_full),
                "filas_test": len(X_test),
                "num_features": len(feature_cols),
                "cv_folds": cv_folds,
                "n_trials_solicitados": n_trials,
                "n_trials_completados": trials_completed,
                "objetivo_pesos": {
                    "F1_cv": 0.40,
                    "win_rate": 0.30,
                    "profit_factor": 0.20,
                    "sharpe": 0.10,
                },
            },
        }

        write_response("OPTIMIZE_RESPONSE", resultado)
        logger.info("=== engine_optimize.py finalizado con éxito ===")

    except Exception as exc:
        logger.error("Fallo durante la optimización: %s", str(exc), exc_info=True)
        write_error("ERROR", str(exc))
        sys.exit(1)


if __name__ == "__main__":
    main()
