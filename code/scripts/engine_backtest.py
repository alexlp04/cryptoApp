import sys
import pandas as pd
import numpy as np
import os
import traceback
import logging
from ipc_protocol import read_request_payload, write_response, write_error
from shared_utils import load_strategy_by_path, setup_engine_logging, CODE_DIR

# --- IMPORTAR MOTOR COMPARTIDO ---
if CODE_DIR not in sys.path:
    sys.path.append(CODE_DIR)

from backtest_engine import (  # noqa: E402
    crear_carpeta_estrategia,
    guardar_trade_a_csv,
    run_backtest,
    run_backtest_with_predictions,
)
from engine_ai_rt import load_model  # noqa: E402

logger = setup_engine_logging("engine_backtest")


def _generar_predicciones(
    model,
    model_type: str,
    strategy,
    df: pd.DataFrame,
    label_classes: list[int] | None = None,
) -> np.ndarray:
    """
    Genera un array de predicciones en el espacio original (−1/0/1) para cada fila del df.

    Para deep_learning: el modelo tiene capa softmax → shape (n, n_clases).
      - Se usa argmax para obtener el índice de clase predicho.
      - Se decodifica con label_classes [{-1,0,1} → índices {0,1,2}].
    Para ml_standard: el modelo fue entrenado con LabelEncoder; si tiene el atributo
      label_encoder se hace inverse_transform al espacio original.
    """
    feature_cols = strategy.get_feature_columns(df)
    x = (
        df[feature_cols]
        .replace([float("inf"), float("-inf")], float("nan"))
        .fillna(0.0)
        .astype("float32")
        .to_numpy()
    )

    if model_type == "deep_learning":
        raw = model.predict(x, verbose=0)  # shape (n, n_clases)
        if raw.ndim == 2 and raw.shape[1] > 1:
            # Multiclase softmax: argmax → índice de clase predicho
            encoded_preds = np.argmax(raw, axis=1)
        else:
            # Fallback binario (sigmoid escalar)
            probs = raw.flatten()
            encoded_preds = np.where(probs >= 0.55, 1, np.where(probs <= 0.45, 0, -99)).astype(int)

        if label_classes is not None:
            # Decodificar índice → valor original: índice 0 → label_classes[0], etc.
            lc = np.array(label_classes, dtype=int)
            # Clamp por si hay índices fuera de rango
            encoded_preds = np.clip(encoded_preds, 0, len(lc) - 1)
            preds = lc[encoded_preds]
        else:
            # Sin metadata: asumir 3 clases {0→-1, 1→0, 2→1}
            n_cls = raw.shape[1] if raw.ndim == 2 else 2
            preds = encoded_preds - (n_cls // 2)

    else:
        encoded_preds = model.predict(x).astype(int)
        label_encoder = getattr(model, "label_encoder", None)
        if label_encoder is not None:
            preds = label_encoder.inverse_transform(encoded_preds).astype(int)
        else:
            preds = encoded_preds

    return preds


def main() -> None:
    try:
        logger.info("Backtest engine started")

        # 1. Read input
        payload = read_request_payload()
        strategy_path = payload.get("strategy_path")
        strategy_name = payload.get("strategy_name")
        timeframe = payload.get("timeframe")
        velas = payload.get("velas", {})
        capital = payload.get("capital", 1000)
        risk_per_trade = payload.get("risk_per_trade", 0.02)
        escribir_trades = payload.get("escribir_trades", False)
        model_name = payload.get("model_name")  # opcional — None → backtest clásico

        logger.info("Loading strategy from: %s", strategy_path)
        strategy = load_strategy_by_path(strategy_path, capital=capital, risk_per_trade=risk_per_trade)
        setattr(strategy, "timeframe", timeframe)

        # Cargar modelo si se especificó
        model = None
        model_type = None
        label_classes = None
        if model_name:
            logger.info("Modo backtest con modelo IA: %s", model_name)
            # Se carga el modelo una sola vez; symbol se infiere del primer símbolo del payload
            first_symbol = next(iter(velas), "UNKNOWN")
            model, model_type, label_classes = load_model(model_name, timeframe, first_symbol)
            logger.info("Modelo cargado: %s (%s) label_classes=%s", model_name, model_type, label_classes)

        # Create strategy results folder
        carpeta_estrategia = crear_carpeta_estrategia(strategy_name)
        logger.info("Results folder: %s", carpeta_estrategia)

        stats_list = []
        total_trades = 0

        logger.info("Processing %d symbols", len(velas))
        for symbol, candles in velas.items():
            if not candles:
                continue
            df = pd.DataFrame(candles)
            for col in ("open", "high", "low", "close", "volume"):
                if col in df.columns:
                    df[col] = pd.to_numeric(df[col], errors="coerce")
            df["timestamp"] = pd.to_numeric(df["timestamp"], errors="coerce")
            df = df.dropna(subset=["close", "timestamp"])
            if df.empty:
                continue

            if model is not None:
                # Backtest con predicciones del modelo IA
                df_con_indicadores = strategy.populate_indicators(df.copy())
                predictions = _generar_predicciones(model, model_type, strategy, df_con_indicadores, label_classes)
                stats = run_backtest_with_predictions(strategy, df_con_indicadores, predictions, symbol)
                trade_count = stats.get("op_totales", 0)
                logger.info(
                    "Backtest IA para %s: %d ops, win_rate=%.1f%%, pf=%.4f",
                    symbol, trade_count, stats.get("win_rate", 0), stats.get("profit_factor", 0),
                )
            else:
                # Backtest clásico con señales de la estrategia
                trade_count, stats = run_backtest(
                    strategy, df, symbol,
                    carpeta_estrategia=carpeta_estrategia,
                    timeframe=timeframe,
                    escribir_trades=escribir_trades,
                )
                logger.info("Backtest clásico para %s: %d trades, resultado: %s",
                            symbol, trade_count, stats.get("resultado"))

            total_trades += trade_count
            stats_list.append(stats)

        logger.info("Backtest completed successfully. Total trades: %d", total_trades)
        write_response("BACKTEST_RESPONSE", {
            "status": "success",
            "total_trades": total_trades,
            "stats": stats_list,
            "mode": "ai_model" if model_name else "classic",
        })

    except Exception as e:
        error_msg = "Backtest engine error: %s\n%s" % (str(e), traceback.format_exc())
        logger.error(error_msg)
        write_error("ERROR", error_msg)
        sys.exit(1)


if __name__ == "__main__":
    main()