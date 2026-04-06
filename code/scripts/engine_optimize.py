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

warnings.filterwarnings("ignore")
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
OVERFIT_PENALTY_THRESHOLD: float = 0.05   # gap > 5 % → penalizar
OVERFIT_PENALTY_FACTOR: float = 2.0
MIN_SAMPLES_REQUIRED: int = 50
RESULTS_SUBDIR: str = "optimize"

# Pesos de la función objetivo multi-métrica
W_F1_CV: float = 0.40       # F1 en cross-validation (calidad de clasificación)
W_WIN_RATE: float = 0.30    # Win rate de la simulación de trading en validación
W_PROFIT_FACTOR: float = 0.20  # Profit factor normalizado
W_SHARPE: float = 0.10      # Sharpe ratio normalizado de la curva de capital

# Parámetros de la simulación de trading interna
INITIAL_CAPITAL_SIM: float = 10_000.0
RISK_PER_TRADE_SIM: float = 0.02  # 2 % de capital por trade
CAP_PROFIT_FACTOR: float = 5.0    # normalización del profit factor


# =============================================================================
# SIMULACIÓN DE TRADING INTERNA (señales del modelo → trades simulados)
# =============================================================================

def _simulate_trades(
    predictions: np.ndarray,
    prices: np.ndarray,
    timestamps: np.ndarray,
) -> dict[str, Any]:
    """
    Simula trades a partir de las predicciones del modelo sobre precios reales.

    Convención de labels sobre el dataset de validación:
      - Clase más alta (ej. 1 en binario, 2 en ternario) → señal LONG
      - Resto → sin posición

    Devuelve un dict con win_rate, profit_factor, sharpe, n_trades y lista de trades.
    """
    capital = INITIAL_CAPITAL_SIM
    risk = RISK_PER_TRADE_SIM

    # La señal de compra es la clase máxima del label_encoder (1 → sube, binario; 2 → buy, ternario)
    n_classes = int(predictions.max()) + 1 if len(predictions) > 0 else 2
    buy_class = n_classes - 1

    in_position = False
    entry_price = 0.0
    position_size = 0.0
    trades: list[dict[str, Any]] = []
    capital_curve: list[float] = [capital]
    pos_pnl = 0.0
    neg_pnl = 0.0
    n_wins = 0
    n_losses = 0

    for i, (pred, price, ts) in enumerate(zip(predictions, prices, timestamps)):
        price = float(price)
        if price <= 0:
            continue

        if not in_position and int(pred) == buy_class:
            in_position = True
            entry_price = price
            position_size = (capital * risk) / price
            trades.append({
                "side": "BUY",
                "price": round(price, 8),
                "timestamp": int(ts),
                "pnl": None,
                "capital": round(capital, 4),
            })

        elif in_position and int(pred) != buy_class:
            in_position = False
            pnl = position_size * (price - entry_price)
            capital += pnl
            capital_curve.append(capital)
            if pnl > 0:
                n_wins += 1
                pos_pnl += pnl
            elif pnl < 0:
                n_losses += 1
                neg_pnl += abs(pnl)
            trades.append({
                "side": "SELL",
                "price": round(price, 8),
                "timestamp": int(ts),
                "pnl": round(pnl, 4),
                "capital": round(capital, 4),
            })

    # Cerrar posición abierta al final si queda
    if in_position and len(prices) > 0:
        price = float(prices[-1])
        pnl = position_size * (price - entry_price)
        capital += pnl
        capital_curve.append(capital)
        if pnl > 0:
            n_wins += 1
            pos_pnl += pnl
        elif pnl < 0:
            n_losses += 1
            neg_pnl += abs(pnl)
        trades.append({
            "side": "SELL_EOD",
            "price": round(price, 8),
            "timestamp": int(timestamps[-1]),
            "pnl": round(pnl, 4),
            "capital": round(capital, 4),
        })

    n_closed = n_wins + n_losses
    win_rate = n_wins / n_closed if n_closed > 0 else 0.0
    profit_factor = pos_pnl / neg_pnl if neg_pnl > 0 else (float(pos_pnl) if pos_pnl > 0 else 0.0)
    # Clamp profit_factor para normalización
    profit_factor_norm = min(profit_factor, CAP_PROFIT_FACTOR) / CAP_PROFIT_FACTOR

    # Sharpe sobre curva de capital (retornos período a período)
    sharpe = 0.0
    if len(capital_curve) > 2:
        arr = np.array(capital_curve, dtype=float)
        returns = np.diff(arr) / arr[:-1]
        if returns.std() > 1e-9:
            sharpe = float(returns.mean() / returns.std())

    # Normalizar Sharpe a [0, 1] usando umbral razonable (2.0 = excelente)
    sharpe_norm = max(0.0, min(sharpe / 2.0, 1.0))

    retorno_pct = ((capital - INITIAL_CAPITAL_SIM) / INITIAL_CAPITAL_SIM) * 100.0

    return {
        "win_rate": round(win_rate, 6),
        "profit_factor": round(profit_factor, 6),
        "profit_factor_norm": round(profit_factor_norm, 6),
        "sharpe": round(sharpe, 6),
        "sharpe_norm": round(sharpe_norm, 6),
        "n_trades": n_closed * 2,  # BUY+SELL
        "n_wins": n_wins,
        "n_losses": n_losses,
        "retorno_pct": round(retorno_pct, 4),
        "capital_final": round(capital, 4),
        "trades": trades,
    }


def _compute_composite_score(
    f1_cv: float,
    win_rate: float,
    profit_factor_norm: float,
    sharpe_norm: float,
    overfit_gap: float,
) -> float:
    """
    Función de scoring compuesta (maximizar).
    Combina calidad de clasificación + métricas de trading reales.
    Penaliza overfitting.
    """
    base = (
        W_F1_CV * f1_cv
        + W_WIN_RATE * win_rate
        + W_PROFIT_FACTOR * profit_factor_norm
        + W_SHARPE * sharpe_norm
    )
    penalty = max(0.0, overfit_gap - OVERFIT_PENALTY_THRESHOLD) * OVERFIT_PENALTY_FACTOR
    return base - penalty


# =============================================================================
# CARGA DE ESTRATEGIA (reutiliza misma lógica que engine_train.py)
# =============================================================================

def load_strategy(strategy_name: str) -> Any:
    """Carga una estrategia dinámicamente y valida que herede de BaseStrategy."""
    if not strategy_name or not strategy_name.strip():
        raise ValueError("strategy_name está vacío o no es válido.")

    strategy_name = strategy_name.strip()
    if code_dir not in sys.path:
        sys.path.insert(0, code_dir)

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

    return strategy_class()


# =============================================================================
# PREPARACIÓN DE FEATURES (reutiliza misma lógica que engine_train.py)
# =============================================================================

def apply_strategy_features(
    df: pd.DataFrame,
    strategy: Any,
    warmup_candles: int,
) -> tuple[pd.DataFrame, pd.Series]:
    """Aplica indicadores/labels de estrategia y devuelve X, y alineados."""
    if "timestamp" not in df.columns and df.index.name == "timestamp":
        df = df.reset_index()
    if "timestamp" in df.columns:
        df = df.sort_values("timestamp")

    logger.info("Iniciando populate_indicators para estrategia '%s'", strategy.get_name())
    t0 = datetime.now()
    df_enriched = strategy.populate_indicators(df.copy())
    logger.info(
        "populate_indicators finalizado en %.2fs (filas=%d)",
        (datetime.now() - t0).total_seconds(),
        len(df_enriched),
    )

    warmup = max(int(warmup_candles or 0), 0)
    if warmup > 0:
        logger.info("Aplicando warmup: descartando primeras %d velas", warmup)
        df_enriched = df_enriched.iloc[warmup:].copy()

    if len(df_enriched) < 2:
        raise ValueError("No hay suficientes velas tras aplicar indicadores y warmup.")

    feature_cols = strategy.get_feature_columns(df_enriched)
    if not feature_cols:
        raise ValueError("La estrategia no devolvió columnas de features (get_feature_columns vacío).")

    current_rows = df_enriched.iloc[:-1].copy()
    next_rows = df_enriched.iloc[1:].copy()

    labels: list[int] = [
        strategy.get_label(row._asdict(), next_row._asdict())
        for row, next_row in zip(
            current_rows.itertuples(index=False, name="Row"),
            next_rows.itertuples(index=False, name="NextRow"),
        )
    ]

    y = pd.Series(labels, index=current_rows.index, name="Target")
    x_features = current_rows[feature_cols].copy()

    x_features = x_features.replace([float("inf"), float("-inf")], float("nan"))
    valid_mask = ~x_features.isna().any(axis=1)
    dropped = int((~valid_mask).sum())
    if dropped > 0:
        logger.warning("Se descartaron %d filas por NaN/inf en features.", dropped)
    x_features = x_features.loc[valid_mask]
    y = y.loc[valid_mask]

    if len(x_features) < MIN_SAMPLES_REQUIRED:
        raise ValueError("No hay suficientes datos limpios tras aplicar la estrategia dinámica.")

    logger.info(
        "Feature engineering completado: filas_validas=%d, features=%d, labels=%s",
        len(x_features),
        len(feature_cols),
        y.value_counts(dropna=False).to_dict(),
    )
    return x_features, y


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

    skf = StratifiedKFold(n_splits=cv_folds, shuffle=True, random_state=42)
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
        layers.append(Dense(1, activation="sigmoid"))

        model = Sequential(layers)
        model.compile(
            optimizer=Adam(learning_rate=lr),
            loss="binary_crossentropy",
            metrics=["accuracy"],
        )
        model.fit(X_tr, y_tr, epochs=epochs, batch_size=batch_size,
                  verbose=0, validation_split=0.0)
        y_pred = (model.predict(X_val, verbose=0) > 0.5).astype(int).flatten()
        cv_scores.append(float(accuracy_score(y_val, y_pred)))
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
    layers_full.append(Dense(1, activation="sigmoid"))
    model_full = Sequential(layers_full)
    model_full.compile(
        optimizer=Adam(learning_rate=lr),
        loss="binary_crossentropy",
        metrics=["accuracy"],
    )
    model_full.fit(X, y, epochs=epochs, batch_size=batch_size, verbose=0)
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
    prices: np.ndarray,
    timestamps: np.ndarray,
    cv_folds: int,
    trial_log: list[dict[str, Any]],
) -> Any:
    """
    Cierra sobre X, y, prices, timestamps y devuelve la función objetivo multi-métrica para Optuna.

    Puntuación compuesta:
      score = W_F1_CV * F1_cv + W_WIN_RATE * win_rate_cv + W_PROFIT_FACTOR * pf_norm + W_SHARPE * sharpe_norm
              - penalización_overfitting
    """
    suggest_fn = _SUGGEST_FN.get(model_type, _suggest_random_forest)
    is_neural = model_type in ("neural_network", "deep_learning", "keras")
    skf = StratifiedKFold(n_splits=cv_folds, shuffle=True, random_state=42)

    def objective(trial: optuna.Trial) -> float:
        params = suggest_fn(trial)
        trial_start = datetime.now()

        try:
            # ─ Métricas de clasificación por fold + simulación de trading ─────────
            cv_f1_scores: list[float] = []
            cv_acc_scores: list[float] = []
            cv_win_rates: list[float] = []
            cv_pf_norms: list[float] = []
            cv_sharpe_norms: list[float] = []

            if is_neural:
                # Para NN reutilizamos la función existente (sin simulación de trading por fold)
                cv_acc, train_acc = _build_and_eval_neural_network(X, y, params, cv_folds)
                # NN: calculamos F1 aproximado como proxy del accuracy
                f1_cv = cv_acc
                win_rate_cv = cv_acc  # no hay trading real en este modo
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

                    # Simulación de trading sobre el fold de validación
                    prices_val = prices[val_idx]
                    ts_val = timestamps[val_idx]
                    sim = _simulate_trades(y_pred_val, prices_val, ts_val)
                    cv_win_rates.append(sim["win_rate"])
                    cv_pf_norms.append(sim["profit_factor_norm"])
                    cv_sharpe_norms.append(sim["sharpe_norm"])
                    logger.debug(
                        "  fold %d/%d | f1=%.4f acc=%.4f win_rate=%.4f pf=%.4f sharpe=%.4f",
                        fold_idx + 1, cv_folds, fold_f1, fold_acc,
                        sim["win_rate"], sim["profit_factor"], sim["sharpe"],
                    )

                f1_cv = float(np.mean(cv_f1_scores))
                cv_acc = float(np.mean(cv_acc_scores))
                win_rate_cv = float(np.mean(cv_win_rates))
                pf_norm_cv = float(np.mean(cv_pf_norms))
                sharpe_norm_cv = float(np.mean(cv_sharpe_norms))

                # Entrenamiento completo para medir gap de overfitting
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

        norm = Normalization()
        norm.adapt(X)
        layers: list[Any] = [norm]
        for _ in range(hidden_layers):
            layers.append(Dense(neurons, activation="relu"))
            layers.append(Dropout(dropout))
        layers.append(Dense(1, activation="sigmoid"))

        model = Sequential(layers)
        model.compile(
            optimizer=Adam(learning_rate=lr),
            loss="binary_crossentropy",
            metrics=["accuracy"],
        )
        model.fit(X, y, epochs=epochs, batch_size=batch_size,
                  verbose=0, validation_split=0.1)

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
# GUARDADO DE CSV DE TRADES (igual contrato que engine_backtest.py)
# =============================================================================

def _save_trades_csv(
    trades: list[dict[str, Any]],
    model_type: str,
    timeframe: str,
    symbol: str,
    strategy_name: str | None,
) -> str:
    """Guarda los trades individuales del modelo final y devuelve la ruta relativa."""
    import csv as _csv
    results_dir = os.path.join(project_root, "results", RESULTS_SUBDIR)
    os.makedirs(results_dir, exist_ok=True)

    suffix = f"_{strategy_name}" if strategy_name else ""
    csv_filename = f"optimize_trades_{model_type}_{timeframe}_{symbol}{suffix}.csv"
    csv_path = os.path.join(results_dir, csv_filename)

    with open(csv_path, "w", newline="", encoding="utf-8") as fh:
        writer = _csv.writer(fh)
        writer.writerow(["symbol", "timeframe", "side", "price", "timestamp", "pnl", "capital"])
        for t in trades:
            writer.writerow([
                symbol,
                timeframe,
                t.get("side", ""),
                t.get("price", ""),
                t.get("timestamp", ""),
                t.get("pnl", ""),
                t.get("capital", ""),
            ])

    logger.info("CSV de trades guardado en: %s (%d entradas)", csv_path, len(trades))
    return os.path.join("results", RESULTS_SUBDIR, csv_filename)


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
            X_df, y_ser = apply_strategy_features(df.copy(), strategy, int(warmup_candles or 0))
            feature_cols = list(X_df.columns)
            # Capturar precios OHLCV del df enriquecido alineados con X_df
            # df.reset_index() tiene timestamp como columna; X_df usa ese mismo índice
            df_for_prices = df.copy()
            if df_for_prices.index.name == "timestamp":
                df_for_prices = df_for_prices.reset_index()
            df_for_prices = df_for_prices.sort_values("timestamp").set_index("timestamp")
            # X_df.index contiene los timestamps alineados con features (tras warmup y dropna)
            prices_aligned = df_for_prices.reindex(X_df.index)["close"]
            # Rellenar posibles NaN con interpolación forward
            prices_aligned = prices_aligned.ffill().fillna(0.0)
        else:
            # Flujo legacy: target binario por siguiente vela
            df["Target"] = (df["close"].shift(-1) > df["close"]).astype(int)
            rows_before = len(df)
            df.dropna(inplace=True)
            rows_dropped = rows_before - len(df)
            if rows_dropped > 0:
                pct = rows_dropped / rows_before * 100
                logger.info("Filas descartadas por NaN/Inf: %d (%.1f%%)", rows_dropped, pct)
                if pct > 50:
                    logger.warning(
                        "Se perdió más del 50%% de filas. Considera aumentar los días."
                    )
            if len(df) < MIN_SAMPLES_REQUIRED:
                raise ValueError(
                    f"Datos insuficientes tras limpieza: {len(df)} filas "
                    f"(mínimo {MIN_SAMPLES_REQUIRED})."
                )
            X_df = df.drop(columns=["Target"])
            y_ser = df["Target"]
            feature_cols = list(X_df.columns)

        X_np = X_df.values.astype(float)
        y_np = y_ser.values

        # ── Extraer precios y timestamps alineados con X/y para simulación de trading ──
        if strategy_name:
            # prices_aligned fue construido arriba con el df enriquecido
            prices_all = prices_aligned.values.astype(float)
        elif "close" in X_df.columns:
            prices_all = X_df["close"].values.astype(float)
        else:
            prices_all = np.zeros(len(X_np), dtype=float)
            logger.warning("No se encontró columna 'close'; simulación de trading usará precios=0.")

        # Timestamps como array numérico (el índice de X_df es el timestamp)
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
        prices_train = prices_all[:split_idx]
        prices_test = prices_all[split_idx:]
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

        # Liberación de memoria
        del df, X_df, y_ser, prices_all, timestamps_all
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
            "Fórmula objetivo compuesta: F1_cv*%.2f + WinRate*%.2f + PF_norm*%.2f + Sharpe_norm*%.2f - penalización_overfitting",
            W_F1_CV, W_WIN_RATE, W_PROFIT_FACTOR, W_SHARPE,
        )

        study = optuna.create_study(
            direction="maximize",
            pruner=MedianPruner(n_startup_trials=5, n_warmup_steps=10),
            study_name=f"optimize_{model_type}_{symbol}_{timeframe}",
        )

        objective_fn = _make_objective(
            model_type, X_train_full, y_train_full,
            prices_train, timestamps_train,
            cv_folds, trial_log,
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

        # ── Simulación de trading verídica sobre el conjunto de TEST ─────────
        logger.info("Ejecutando simulación de trading sobre el conjunto de TEST (20%%)...")
        is_neural_final = model_type in ("neural_network", "deep_learning", "keras")
        if is_neural_final:
            import tensorflow as _tf_final  # noqa: F401 — importación diferida
            y_test_pred_final = (final_model.predict(X_test, verbose=0) > 0.5).astype(int).flatten()
        else:
            y_test_pred_final = final_model.predict(X_test)

        test_sim = _simulate_trades(y_test_pred_final, prices_test, timestamps_test)
        logger.info(
            "Simulación test: n_trades=%d win_rate=%.4f profit_factor=%.4f "
            "sharpe=%.4f retorno_pct=%.2f%% capital_final=%.2f",
            test_sim["n_trades"], test_sim["win_rate"], test_sim["profit_factor"],
            test_sim["sharpe"], test_sim["retorno_pct"], test_sim["capital_final"],
        )

        # ── CSV de trades del modelo final ───────────────────────────────────
        trades_csv_path = ""
        if test_sim["trades"]:
            trades_csv_path = _save_trades_csv(
                test_sim["trades"], model_type, timeframe, symbol, strategy_name
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
            "trades_csv_path": trades_csv_path,
            "final_metrics": {
                "accuracy": round(final_metrics["accuracy"] * 100, 2),
                "precision": round(final_metrics["precision"] * 100, 2),
                "recall": round(final_metrics["recall"] * 100, 2),
                "f1": round(final_metrics["f1"] * 100, 2),
            },
            "trading_simulation_test": {
                "n_trades": test_sim["n_trades"],
                "n_wins": test_sim["n_wins"],
                "n_losses": test_sim["n_losses"],
                "win_rate": round(test_sim["win_rate"] * 100, 2),
                "profit_factor": round(test_sim["profit_factor"], 4),
                "sharpe": round(test_sim["sharpe"], 4),
                "retorno_pct": round(test_sim["retorno_pct"], 4),
                "capital_inicial": INITIAL_CAPITAL_SIM,
                "capital_final": round(test_sim["capital_final"], 2),
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
                    "F1_cv": W_F1_CV,
                    "win_rate": W_WIN_RATE,
                    "profit_factor": W_PROFIT_FACTOR,
                    "sharpe": W_SHARPE,
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
