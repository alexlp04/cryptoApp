"""engine_train.py — Motor de entrenamiento de modelos ML/DL para señales de trading.

Protocolo IPC:
  Entrada : MessagePack framed (4 bytes big-endian + envelope) con type TRAIN_REQUEST
  Salida  : MessagePack framed (4 bytes big-endian + envelope) con type TRAIN_RESPONSE
"""
from __future__ import annotations

import gc
import json
import os
import sys
import time
import warnings
from typing import Any

import joblib
import numpy as np
import pandas as pd
from backtest_engine import run_backtest_with_predictions
from ipc_protocol import read_request_payload, write_error, write_response
from shared_utils import (
    PROJECT_ROOT,
    apply_strategy_features,
    build_and_train_neural_network,
    fit_sklearn_model,
    load_strategy_by_name,
    setup_engine_logging,
)
from sklearn.metrics import accuracy_score, f1_score, precision_score, recall_score
from sklearn.preprocessing import LabelEncoder

warnings.filterwarnings("ignore", category=UserWarning)

logger = setup_engine_logging("engine_train", stream=sys.stderr)


# =============================================================================
# ENTRY POINT
# =============================================================================

def main() -> None:
    logger.info("=== Iniciando proceso de entrenamiento masivo (engine_train.py) ===")
    try:
        payload = read_request_payload()

        model_type: str = str(payload.get("model_type", "random_forest")).lower().strip()
        symbol: str = str(payload.get("symbol", "UNKNOWN"))
        timeframe: str = str(payload.get("timeframe", "UNKNOWN"))
        dataset: list = payload.get("dataset", [])
        strategy_name: str | None = payload.get("strategy_name") or None
        # Clave obligatoria del contrato: sin ella se entrenaria sobre las velas de
        # calentamiento, con los indicadores a medio formar y sin ningun error visible.
        if payload.get("warmup_candles") is None:
            raise ValueError(
                "warmup_candles es obligatorio en el payload de entrenamiento: "
                "sin el, el modelo se entrena sobre velas con indicadores incompletos."
            )
        warmup_candles: int = int(payload["warmup_candles"])
        hyperparams: dict = payload.get("hyperparameters", {})

        if not dataset:
            raise ValueError("El dataset esta vacio.")

        if not strategy_name:
            raise ValueError(
                "strategy_name es obligatorio: los indicadores deben calcularse siempre "
                "a traves de populate_indicators() de la estrategia. "
                "Especifica una estrategia valida con --strategy."
            )

        logger.info(
            "Parametros: model=%s symbol=%s tf=%s strategy=%s",
            model_type, symbol, timeframe, strategy_name,
        )

        # Preparar DataFrame
        df_raw = pd.DataFrame(dataset)
        df_raw = df_raw.sort_values("timestamp")
        df_raw = df_raw.reset_index(drop=True)

        # Enriquecer con indicadores de la estrategia (una sola vez)
        strategy = load_strategy_by_name(strategy_name)
        import time as _time
        logger.info("Iniciando populate_indicators para estrategia '%s'", strategy_name)
        t0 = _time.perf_counter()
        df_enriched_full = strategy.populate_indicators(df_raw.copy())
        logger.info(
            "populate_indicators finalizado en %.2fs (filas=%d)",
            _time.perf_counter() - t0, len(df_enriched_full),
        )

        # Feature engineering (already_enriched=True: no repetir populate_indicators)
        x_df, y_ser = apply_strategy_features(
            df_enriched_full.copy(), strategy, warmup_candles, already_enriched=True
        )
        feature_cols = list(x_df.columns)
        total_rows_used = len(x_df)

        # Timestamps para recuperar la porcion de test del df enriquecido
        timestamps_all = x_df.index.values.astype(np.int64)
        x_np = x_df.values.astype(float)
        y_np = y_ser.values

        # Division 80/20
        split_idx = int(len(x_np) * 0.8)
        X_train, X_test = x_np[:split_idx], x_np[split_idx:]
        y_train_raw, y_test_raw = y_np[:split_idx], y_np[split_idx:]
        timestamps_test = timestamps_all[split_idx:]

        # LabelEncoder (P1.3): recodifica {-1,0,1} -> {0,1,2} para XGBoost/LightGBM/NN
        label_encoder = LabelEncoder()
        y_train = label_encoder.fit_transform(y_train_raw)
        y_test = label_encoder.transform(y_test_raw)
        n_classes = len(label_encoder.classes_)

        label_distribution = {
            str(label): int(count)
            for label, count in pd.Series(y_test_raw)
            .value_counts(dropna=False)
            .to_dict()
            .items()
        }
        logger.info(
            "Labels codificados: %s -> indices 0..%d | distribucion test: %s",
            label_encoder.classes_.tolist(), n_classes - 1, label_distribution,
        )

        # Liberar matrices de features (P3.3: sin hack de globals())
        del x_df, y_ser, x_np, y_np
        gc.collect()

        models_dir = os.path.join(PROJECT_ROOT, "models")
        os.makedirs(models_dir, exist_ok=True)

        start_time = time.monotonic()
        is_deep_learning = model_type in ("neural_network", "deep_learning", "keras")

        # Entrenamiento
        if is_deep_learning:
            logger.info(
                "Seleccionado: Red Neuronal Profunda (Deep Learning) - n_clases=%d", n_classes
            )
            from sklearn.utils.class_weight import compute_class_weight
            _cw = compute_class_weight("balanced", classes=np.unique(y_train), y=y_train)
            _cw_dict: dict[int, float] = dict(zip(np.unique(y_train).tolist(), _cw.tolist()))
            logger.info("class_weight (NN train): %s", _cw_dict)
            model, y_pred = build_and_train_neural_network(
                X_train, y_train, X_test, hyperparams, n_classes=n_classes,
                class_weight=_cw_dict,
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
                        "label_classes": label_encoder.classes_.tolist(),
                    },
                    mf,
                    indent=2,
                )
            logger.info("Red Neuronal guardada en: %s", model_path)

        else:
            logger.info("Seleccionado: Modelo ML clasico (%s)", model_type.upper())
            from sklearn.utils.class_weight import compute_class_weight
            _cw_sk = compute_class_weight("balanced", classes=np.unique(y_train), y=y_train)
            _cw_sk_dict: dict[int, float] = dict(zip(np.unique(y_train).tolist(), _cw_sk.tolist()))
            logger.info("class_weight (sklearn train): %s", _cw_sk_dict)
            model = fit_sklearn_model(
                model_type,
                hyperparams,
                X_train,
                y_train,
                class_weight=_cw_sk_dict,
            )
            y_pred = model.predict(X_test)

            model.feature_cols = feature_cols
            model.strategy_name = strategy_name
            model.warmup_candles = warmup_candles
            model.label_encoder = label_encoder

            suffix = f"_{strategy_name}" if strategy_name else ""
            model_filename = f"{model_type}_{timeframe}_{symbol}{suffix}.pkl"
            model_path = os.path.join(models_dir, model_filename)
            joblib.dump(model, model_path)
            logger.info("Modelo ML clasico guardado en: %s", model_path)

        training_time = time.monotonic() - start_time

        # Metricas de clasificacion
        avg = "binary" if n_classes <= 2 else "weighted"
        acc = float(accuracy_score(y_test, y_pred))
        prec = float(precision_score(y_test, y_pred, average=avg, zero_division=0))
        rec = float(recall_score(y_test, y_pred, average=avg, zero_division=0))
        f1 = float(f1_score(y_test, y_pred, average=avg, zero_division=0))
        logger.info(
            "Metricas clasificacion: acc=%.4f prec=%.4f rec=%.4f f1=%.4f",
            acc, prec, rec, f1,
        )

        # Backtest real sobre test set con predicciones del modelo (P1.1)
        y_pred_original = label_encoder.inverse_transform(y_pred)
        df_test = df_enriched_full[
            df_enriched_full["timestamp"].isin(set(timestamps_test.tolist()))
        ].sort_values("timestamp").reset_index(drop=True)
        del df_enriched_full
        gc.collect()

        logger.info("Ejecutando backtest real sobre test set (%d velas)...", len(df_test))
        test_stats = run_backtest_with_predictions(
            strategy, df_test, y_pred_original, symbol
        )
        logger.info(
            "Backtest test: op_totales=%d win_rate=%.2f%% pf=%.4f sharpe=%.4f retorno=%.2f%%",
            test_stats["op_totales"], test_stats["win_rate"],
            test_stats["profit_factor"], test_stats.get("sharpe", 0.0),
            test_stats["retorno_acumulado"],
        )

        # Payload de respuesta
        resultado: dict[str, Any] = {
            "status": "success",
            "model_saved_at": model_filename,
            "feature_cols": feature_cols,
            "strategy_name": strategy_name,
            "warmup_candles": warmup_candles,
            "label_distribution": label_distribution,
            "label_classes": label_encoder.classes_.tolist(),
            "metrics": {
                "Accuracy (Precision Global)": f"{acc * 100:.2f}%",
                "Precision (Acierto en subidas)": f"{prec * 100:.2f}%",
                "Recall (Deteccion de subidas)": f"{rec * 100:.2f}%",
                "F1-Score (Balance)": f"{f1 * 100:.2f}%",
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
            "data_info": {
                "modelo_usado": model_type.upper(),
                "tiempo_entrenamiento_seg": round(training_time, 2),
                "total_velas_usadas": total_rows_used,
                "velas_entrenamiento": len(X_train),
                "velas_prueba": len(X_test),
                "feature_cols": feature_cols,
                "strategy_name": strategy_name,
                "warmup_candles": warmup_candles,
                "hiperparametros_aplicados": hyperparams,
            },
        }

        write_response("TRAIN_RESPONSE", resultado)
        logger.info("=== engine_train.py finalizado con exito ===")

    except Exception as exc:
        logger.exception("Fallo durante el proceso")
        write_error("ERROR", str(exc))
        sys.exit(1)


if __name__ == "__main__":
    main()
