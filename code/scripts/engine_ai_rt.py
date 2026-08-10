from __future__ import annotations

import asyncio
import json
import logging
import os
import sys
import types
import warnings
from collections import deque
from typing import Any

import joblib
import numpy as np
import pandas as pd
import websockets
from ipc_protocol import read_request_payload
from shared_utils import (
    CODE_DIR,
    PROJECT_ROOT,
    build_signal_line,
    emit_heartbeats,
    load_strategy_by_path,
    setup_engine_logging,
)

# Ignorar advertencias de Pandas/Scikit-learn sobre nombres de características (Feature names)
warnings.filterwarnings("ignore", category=UserWarning)

logger = setup_engine_logging("engine_ai_rt")

# Evita el ruido de bajo nivel del cliente websocket en el log del bot.
logging.getLogger("websockets").setLevel(logging.WARNING)
logging.getLogger("asyncio").setLevel(logging.WARNING)

if sys.platform == "win32" and "posix" not in sys.modules:
    sys.modules["posix"] = types.ModuleType("posix")

if CODE_DIR not in sys.path:
    sys.path.append(CODE_DIR)

def _find_model_path(base_filename: str, models_dir: str, extensions: tuple[str, ...]) -> str | None:
    """Devuelve el primer path existente que coincida con base_filename + alguna extensión."""
    for ext in extensions:
        path = os.path.join(models_dir, f"{base_filename}{ext}")
        if os.path.exists(path):
            return path
    return None


def _load_label_classes(model_path: str) -> list[int] | None:
    """Lee label_classes del .metadata.json junto al modelo. Devuelve None si no existe."""
    metadata_path = os.path.splitext(model_path)[0] + ".metadata.json"
    if not os.path.exists(metadata_path):
        # intentar .metadata.json si la extensión era .h5
        metadata_path = model_path + ".metadata.json"
    if os.path.exists(metadata_path):
        import json as _json
        with open(metadata_path, encoding="utf-8") as f:
            meta = _json.load(f)
        classes = meta.get("label_classes")
        if classes is not None:
            logger.info("Metadata cargada: label_classes=%s", classes)
            return [int(c) for c in classes]
    return None


# =========================
# CARGA DE MODELO IA UNIVERSAL
# =========================
def load_model(model_name: str, timeframe: str, symbol: str) -> tuple[Any, str, list[int] | None]:
    """
    Carga dinámicamente diferentes tipos de modelos basados en su extensión y librería.
    Soporta Scikit-Learn, XGBoost, LightGBM (.pkl) y TensorFlow/Keras (.h5 / .keras).
    Devuelve (modelo, tipo, label_classes) donde label_classes es la lista original
    de clases [-1, 0, 1] leída del .metadata.json (o None si no existe).
    """
    models_dir = os.path.join(PROJECT_ROOT, 'models')
    base_filename = f"{model_name}_{timeframe}_{symbol}"

    # 0. Buscar por nombre libre (el usuario ha renombrado el modelo)
    for ext in (".pkl", ".keras", ".h5"):
        bare_path = os.path.join(models_dir, f"{model_name}{ext}")
        if os.path.exists(bare_path):
            if ext == ".pkl":
                logger.info("Cargando modelo (nombre libre, PKL): %s", bare_path)
                loaded = joblib.load(bare_path)
                lc = getattr(loaded, "label_encoder", None)
                lc = list(lc.classes_) if lc is not None else None
                return loaded, "ml_standard", lc
            else:
                logger.info("Cargando modelo (nombre libre, Keras/TF): %s", bare_path)
                from tensorflow.keras.models import load_model as load_keras_model
                return load_keras_model(bare_path), "deep_learning", _load_label_classes(bare_path)

    # 1. Buscar modelos estándar (Scikit-Learn, XGBoost, LightGBM)
    pkl_path = os.path.join(models_dir, f"{base_filename}.pkl")
    if os.path.exists(pkl_path):
        logger.info("Cargando modelo clasico (PKL): %s", pkl_path)
        loaded = joblib.load(pkl_path)
        lc = getattr(loaded, "label_encoder", None)
        lc = list(lc.classes_) if lc is not None else None
        return loaded, "ml_standard", lc

    # 2. Buscar modelos de Deep Learning (TensorFlow / Keras)
    dl_path = _find_model_path(base_filename, models_dir, (".keras", ".h5"))
    if dl_path:
        logger.info("Cargando modelo de Red Neuronal (Keras/TF): %s", dl_path)
        from tensorflow.keras.models import load_model as load_keras_model
        return load_keras_model(dl_path), "deep_learning", _load_label_classes(dl_path)

    # 3. Buscar variantes con sufijo de estrategia: {base_filename}_{strategy}.ext
    for fname in os.listdir(models_dir):
        if fname.startswith(f"{base_filename}_") and fname.endswith((".pkl", ".keras", ".h5")):
            candidate = os.path.join(models_dir, fname)
            if fname.endswith(".pkl"):
                logger.info("Cargando modelo con sufijo de estrategia (PKL): %s", candidate)
                loaded = joblib.load(candidate)
                lc = getattr(loaded, "label_encoder", None)
                lc = list(lc.classes_) if lc is not None else None
                return loaded, "ml_standard", lc
            else:
                logger.info("Cargando modelo con sufijo de estrategia (Keras/TF): %s", candidate)
                from tensorflow.keras.models import load_model as load_keras_model
                return load_keras_model(candidate), "deep_learning", _load_label_classes(candidate)

    raise FileNotFoundError(
        f"No se encontró ningún modelo para '{model_name}' "
        f"(buscado como nombre libre, {base_filename}, y variantes con sufijo de estrategia). "
        f"Extensiones probadas: .pkl, .keras, .h5"
    )

# =========================
# SUBFUNCIONES DE PREDICCIÓN
# =========================

def _prepare_feature_row(
    df_buffer: deque,
    strategy,
    feature_cols: list[str],
    symbol: str,
    model_name: str,
):
    """
    Construye y valida la fila de features para predicción.
    Devuelve (ultima_fila_df, x_pred_np) o (None, None) si hay problema.
    """
    df = pd.DataFrame(list(df_buffer))
    df_con_indicadores = strategy.populate_indicators(df.copy())

    actual_feature_cols = strategy.get_feature_columns(df_con_indicadores)
    if not actual_feature_cols:
        logger.warning("[%s] La estrategia no devolvio feature columns; se omite predicción.", symbol)
        return None, None

    missing_cols = [col for col in actual_feature_cols if col not in df_con_indicadores.columns]
    if missing_cols:
        logger.error(
            "[%s] Feature columns faltantes para %s. Esperadas: %s. Faltantes: %s.",
            symbol, model_name, actual_feature_cols, missing_cols,
        )
        return None, None

    ultima_fila = df_con_indicadores[actual_feature_cols].iloc[[-1]].copy()

    if list(ultima_fila.columns) != actual_feature_cols:
        logger.warning(
            "[%s] Orden de columnas diferente. Reordenando.", symbol,
        )
        ultima_fila = ultima_fila[actual_feature_cols]

    ultima_fila = (
        ultima_fila
        .apply(pd.to_numeric, errors="coerce")
        .replace([float("inf"), float("-inf")], float("nan"))
        .fillna(0.0)
        .astype("float32")
    )
    return ultima_fila, ultima_fila.to_numpy(dtype="float32", copy=False)


def _predict_ml_standard(model, x_pred, model_name: str, symbol: str) -> str | None:
    """Ejecuta predicción con modelos sklearn/XGBoost/LightGBM y devuelve la acción."""
    prediccion = model.predict(x_pred)[0]
    clases_modelo = list(getattr(model, "classes_", []))
    try:
        probabilidades = model.predict_proba(x_pred)[0]
        prob_por_clase = (
            {clase: float(p) for clase, p in zip(clases_modelo, probabilidades)}
            if clases_modelo and len(clases_modelo) == len(probabilidades)
            else {idx: float(p) for idx, p in enumerate(probabilidades)}
        )

        if -1 in prob_por_clase or len(prob_por_clase) > 2:
            prob_buy = prob_por_clase.get(1, 0.0)
            prob_hold = prob_por_clase.get(0, 0.0)
            prob_sell = prob_por_clase.get(-1, 0.0)
            logger.info(
                "[%s] %s Predice: %s (BUY: %.1f%%, HOLD: %.1f%%, SELL: %.1f%%)",
                symbol, model_name.upper(), prediccion,
                prob_buy * 100, prob_hold * 100, prob_sell * 100,
            )
            if int(prediccion) == 1:
                return "BUY"
            if int(prediccion) == -1:
                return "SELL"
        else:
            prob_baja = prob_por_clase.get(0, probabilidades[0])
            prob_sube = prob_por_clase.get(1, probabilidades[1] if len(probabilidades) > 1 else 0.0)
            logger.info(
                "[%s] %s Predice: %s (Sube: %.1f%%, Baja: %.1f%%)",
                symbol, model_name.upper(), prediccion, prob_sube * 100, prob_baja * 100,
            )
            if int(prediccion) == 1 and prob_sube >= 0.52:
                return "BUY"
            if int(prediccion) == 0 and prob_baja >= 0.52:
                return "SELL"

    except AttributeError:
        logger.info("[%s] %s Predice: %s (Binario)", symbol, model_name.upper(), prediccion)
        if int(prediccion) == 1:
            return "BUY"
        if int(prediccion) == -1:
            return "SELL"
        if int(prediccion) == 0 and len(clases_modelo) <= 2:
            return "SELL"

    return None


def _predict_deep_learning(
    model,
    x_pred,
    model_name: str,
    symbol: str,
    label_classes: list[int] | None = None,
) -> str | None:
    """Ejecuta predicción con modelos TensorFlow/Keras y devuelve la acción.

    Soporta tanto modelos binarios (sigmoid escalar) como multiclase (softmax).
    Usa label_classes para decodificar el índice predicho al espacio original.
    """
    raw = model.predict(x_pred, verbose=0)

    if raw.ndim == 2 and raw.shape[1] > 1:
        # Multiclase softmax — el caso de neural_network entrenado con 3 clases
        probs = raw[0]
        encoded_idx = int(np.argmax(probs))
        if label_classes is not None:
            original = int(label_classes[encoded_idx])
        else:
            # fallback: 3 clases {0→-1, 1→0, 2→1}
            original = encoded_idx - (len(probs) // 2)

        prob_str = ", ".join(
            f"{cls}:{p:.1%}" for cls, p in zip(
                label_classes if label_classes is not None else range(len(probs)), probs
            )
        )
        logger.info("[%s] RED NEURONAL (multiclase) Predice: %d [%s]", symbol, original, prob_str)

        if original == 1:
            return "BUY"
        if original == -1:
            return "SELL"
        return None

    else:
        # Binario sigmoid escalar
        prob = float(raw[0][0])
        logger.info("[%s] RED NEURONAL (binario) Predice: %.2f%% confianza alcísta", symbol, prob * 100)
        if prob >= 0.55:
            return "BUY"
        if prob <= 0.45:
            return "SELL"
        return None


def _emit_signal(
    symbol: str, action: str, timeframe: str, new_row: dict,
    model_name: str, is_real: bool,
) -> None:
    """Formatea y emite la señal de trading por stdout para que Java la consuma."""
    signal = {
        "symbol": symbol,
        "action": action,
        "timeframe": timeframe,
        "price": float(new_row["close"]),
        "timestamp": int(new_row["timestamp"]),
        "is_real": is_real,
        "source": f"AI_{model_name.upper()}",
    }
    print(build_signal_line(signal), flush=True)
    logger.info("SEÑAL %s ENVIADA: %s para %s a %s", model_name.upper(), action, symbol, signal["price"])


# =========================
# LOOP POR SÍMBOLO
# =========================
async def run_symbol(
    symbol: str,
    timeframe: str,
    strategy_path: str,
    model_name: str,
    capital: float,
    risk_per_trade: float,
    is_real: bool,
    max_candles: int = 100,
    high_activity_test_mode: bool = False,
) -> None:
    clean_symbol = symbol.lower().replace("/", "")
    url = f"wss://stream.binance.com:9443/ws/{clean_symbol}@kline_{timeframe}"

    logger.info("Iniciando AI Stream para %s en %s", symbol, timeframe)
    strategy = load_strategy_by_path(strategy_path, capital, risk_per_trade)
    model, model_type, label_classes = load_model(model_name, timeframe, symbol)
    df_buffer: deque = deque(maxlen=max_candles)
    last_processed_event_id = 0
    feature_cols: list[str] = []

    while True:
        try:
            async with websockets.connect(url) as ws:
                logger.info("Conectado a WebSocket de Binance para %s", symbol)
                async for msg in ws:
                    data = json.loads(msg)
                    k = data["k"]
                    is_candle_closed = k["x"]

                    if not is_candle_closed and not high_activity_test_mode:
                        continue

                    event_id = (
                        int(data.get("E", 0))
                        if (high_activity_test_mode and not is_candle_closed)
                        else int(k["T"])
                    )
                    if event_id == last_processed_event_id:
                        continue
                    last_processed_event_id = event_id

                    new_row = {
                        "timestamp": int(k["t"]),
                        "open": float(k["o"]),
                        "high": float(k["h"]),
                        "low": float(k["l"]),
                        "close": float(k["c"]),
                        "volume": float(k["v"]),
                    }
                    df_buffer.append(new_row)

                    if len(df_buffer) < 50:
                        continue

                    _, x_pred = _prepare_feature_row(df_buffer, strategy, feature_cols, symbol, model_name)
                    if x_pred is None:
                        continue

                    if model_type == "ml_standard":
                        action = _predict_ml_standard(model, x_pred, model_name, symbol)
                    elif model_type == "deep_learning":
                        action = _predict_deep_learning(model, x_pred, model_name, symbol, label_classes)
                    else:
                        action = None

                    if action:
                        _emit_signal(symbol, action, timeframe, new_row, model_name, is_real)

        except websockets.exceptions.ConnectionClosed:
            logger.warning("Conexion WS cerrada para %s. Reconectando...", symbol)
            await asyncio.sleep(2)
        except Exception:
            logger.exception("Error en loop WS de %s", symbol)
            await asyncio.sleep(5)

# =========================
# MAIN
# =========================
def main() -> None:
    logger.info("Motor Python AI RT iniciado. Esperando Payload de Java...")
    try:
        payload = read_request_payload()
        high_activity_test_mode = bool(payload.get("high_activity_test", False))
        logger.info("Configuracion recibida para %d simbolos (high_activity_test=%s)",
                    len(payload.get("symbols", [])), high_activity_test_mode)
        
        model_name = payload.get("model_name")
        if not model_name:
            raise ValueError("El payload de Java no incluye 'model_name'. Imposible arrancar motor IA.")

        asyncio.run(run_all(
            symbols=payload["symbols"],
            timeframe=payload["timeframe"],
            strategy_path=payload["strategy_path"],
            model_name=model_name,
            capital=payload.get("capital", 1000),
            risk_per_trade=payload.get("risk_per_trade", 0.02),
            is_real=payload.get("is_real", False),
            high_activity_test_mode=high_activity_test_mode,
        ))
    except Exception as e:
        logger.critical("Fallo catastrofico en el motor IA: %s", str(e), exc_info=True)
        sys.exit(1)

async def run_all(
    symbols: list[str],
    timeframe: str,
    strategy_path: str,
    model_name: str,
    capital: float,
    risk_per_trade: float,
    is_real: bool,
    high_activity_test_mode: bool = False,
) -> None:
    tasks = [
        run_symbol(sym, timeframe, strategy_path, model_name, capital, risk_per_trade, is_real,
                   high_activity_test_mode=high_activity_test_mode)
        for sym in symbols
    ]
    # Heartbeat de liveness: mantiene vivo el canal RT aunque no haya señales.
    tasks.append(emit_heartbeats())
    await asyncio.gather(*tasks)

if __name__ == "__main__":
    main()
