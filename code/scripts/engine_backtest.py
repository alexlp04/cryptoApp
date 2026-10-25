import sys
import traceback

import numpy as np
import pandas as pd
from ipc_protocol import read_request_payload, write_error, write_response
from shared_utils import (
    CODE_DIR,
    load_strategy_by_path,
    require_name,
    require_symbol,
    require_timeframe,
    setup_engine_logging,
)

# --- IMPORTAR MOTOR COMPARTIDO ---
if CODE_DIR not in sys.path:
    sys.path.append(CODE_DIR)

from backtest_engine import (
    DEFAULT_FEE_RATE,
    DEFAULT_SLIPPAGE,
    EXECUTION_MODES,
    crear_carpeta_estrategia,
    run_backtest,
    run_backtest_with_predictions,
)
from engine_ai_rt import load_model

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
    # Las columnas con las que se entrenó el modelo mandan sobre las que hoy diga la estrategia.
    saved_cols = getattr(model, "feature_cols", None)
    feature_cols = list(saved_cols) if saved_cols else strategy.get_feature_columns(df)
    missing = [c for c in feature_cols if c not in df.columns]
    if missing:
        raise ValueError(f"Faltan las columnas {missing} que el modelo necesita: ¿cambió la estrategia?")
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
            # Sin metadata: 3 clases {0→-1, 1→0, 2→1}; con 2 (sigmoide o softmax binario) ya son 0/1
            # y la banda neutra de la sigmoide (-99) significa "no entrar".
            n_cls = raw.shape[1] if raw.ndim == 2 else 2
            preds = np.maximum(encoded_preds, 0) if n_cls <= 2 else encoded_preds - (n_cls // 2)

    else:
        encoded_preds = model.predict(x).astype(int)
        label_encoder = getattr(model, "label_encoder", None)
        if label_encoder is not None:
            preds = label_encoder.inverse_transform(encoded_preds).astype(int)
        else:
            preds = encoded_preds

    return preds


def _evaluation_window(
    df: pd.DataFrame, model, strategy, include_train: bool, symbol: str,
) -> pd.DataFrame:
    """Velas sobre las que tiene sentido evaluar el modelo.

    Descarta el calentamiento de indicadores (el entrenamiento también lo hace) y, salvo
    include_train, lo anterior al corte de entrenamiento: sobre esas filas el modelo ya
    ha visto las etiquetas y las métricas saldrían infladas.
    """
    warmup = int(getattr(model, "warmup_candles", None) or strategy.get_warmup_period())
    df = df.iloc[warmup:]
    if include_train:
        logger.warning("[%s] include_train=true: las métricas incluyen datos de entrenamiento", symbol)
        return df.reset_index(drop=True)
    cutoff = getattr(model, "train_end_ts", None)
    if cutoff is None:
        logger.warning("[%s] El modelo no guarda su corte de entrenamiento (reentrénalo): "
                       "se evalúa todo el histórico y puede haber datos in-sample", symbol)
        return df.reset_index(drop=True)
    return df[df["timestamp"] > cutoff].reset_index(drop=True)


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
        include_train = bool(payload.get("include_train", False))  # incluir el tramo con el que se entrenó el modelo
        fee_rate = float(payload.get("fee_rate", DEFAULT_FEE_RATE))
        slippage = float(payload.get("slippage", DEFAULT_SLIPPAGE))
        execution = payload.get("execution", "market")
        if execution not in EXECUTION_MODES:
            raise ValueError(f"'execution' debe ser uno de {EXECUTION_MODES}, no {execution!r}.")

        # Campos obligatorios: si faltan, un None viajaría hasta load_strategy_by_path o
        # crear_carpeta_estrategia y fallaría allí de forma opaca. Mismo criterio que
        # engine_train.py: el error se reporta en la frontera del protocolo.
        # Se comprueban uno a uno (y no en bucle) para que el análisis estático pueda
        # descartar el None en el resto de la función.
        if not strategy_path:
            raise ValueError("'strategy_path' es obligatorio en el payload de backtest.")
        if not strategy_name:
            raise ValueError("'strategy_name' es obligatorio en el payload de backtest.")
        if not timeframe:
            raise ValueError("'timeframe' es obligatorio en el payload de backtest.")

        require_name(strategy_name, "strategy_name")  # nombre de la carpeta de resultados
        require_timeframe(timeframe)
        if model_name:
            require_name(model_name, "model_name")

        logger.info("Loading strategy from: %s", strategy_path)
        strategy = load_strategy_by_path(strategy_path, capital=capital, risk_per_trade=risk_per_trade)
        strategy.timeframe = timeframe

        # Los modelos son por símbolo: se cargan (y cachean) dentro del bucle
        models: dict[str, tuple] = {}
        skipped_symbols: list[str] = []
        if model_name:
            logger.info("Modo backtest con modelo IA: %s", model_name)

        # Create strategy results folder
        carpeta_estrategia = crear_carpeta_estrategia(strategy_name)
        logger.info("Results folder: %s", carpeta_estrategia)

        stats_list = []
        total_trades = 0

        logger.info("Processing %d symbols", len(velas))
        for symbol, candles in velas.items():
            require_symbol(symbol)  # acaba en el nombre del CSV de trades y en el del modelo
            if candles is None or len(candles) == 0:
                continue
            df = pd.DataFrame(candles)
            for col in ("open", "high", "low", "close", "volume"):
                if col in df.columns:
                    df[col] = pd.to_numeric(df[col], errors="coerce")
            df["timestamp"] = pd.to_numeric(df["timestamp"], errors="coerce")
            df = df.dropna(subset=["close", "timestamp"])
            df = df[df["close"] > 0]  # close == 0 (dato corrupto) divide por cero al calcular el tamaño de posición
            # Mismo orden que en entrenamiento: pct_change/ewm/rolling dependen de él.
            df = df.sort_values("timestamp").drop_duplicates("timestamp").reset_index(drop=True)
            if df.empty:
                continue

            if model_name:
                if symbol not in models:
                    try:
                        models[symbol] = load_model(model_name, timeframe, symbol, strategy.get_name())
                    except FileNotFoundError as exc:
                        logger.warning("Sin modelo para %s, se omite: %s", symbol, exc)
                        skipped_symbols.append(symbol)
                        continue
                model, model_type, label_classes = models[symbol]
                df_con_indicadores = _evaluation_window(
                    strategy.populate_indicators(df.copy()), model, strategy, include_train, symbol)
                if df_con_indicadores.empty:
                    logger.warning("Sin velas que evaluar para %s tras descartar warmup/entrenamiento", symbol)
                    continue
                predictions = _generar_predicciones(model, model_type, strategy, df_con_indicadores, label_classes)
                stats = run_backtest_with_predictions(
                    strategy, df_con_indicadores, predictions, symbol,
                    fee_rate=fee_rate, slippage=slippage, execution=execution)
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
                    fee_rate=fee_rate, slippage=slippage, execution=execution,
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
            "skipped_symbols": skipped_symbols,
        })

    except Exception as e:  # noqa: BLE001 - frontera del engine: todo fallo se reporta por IPC
        error_msg = f"Backtest engine error: {e}\n{traceback.format_exc()}"
        logger.error(error_msg)
        write_error("ERROR", error_msg)
        sys.exit(1)


if __name__ == "__main__":
    main()