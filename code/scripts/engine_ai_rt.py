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
from risk_management import PositionTracker, strategy_levels
from shared_utils import (
    CODE_DIR,
    PROJECT_ROOT,
    build_signal_line,
    emit_heartbeats,
    fetch_recent_klines,
    load_strategy_by_path,
    seed_buffer,
    setup_engine_logging,
    upsert_candle,
    verify_model_manifest,
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

#: Extensiones reconocidas para un modelo entrenado, en orden de preferencia.
MODEL_EXTENSIONS = (".pkl", ".keras", ".h5")


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
def _load_from_path(path: str) -> tuple[Any, str, list[int] | None]:
    """Carga un modelo ya localizado, eligiendo el backend por su extensión."""
    if path.endswith(".pkl"):
        logger.info("Cargando modelo clasico (PKL): %s", path)
        loaded = joblib.load(path)
        encoder = getattr(loaded, "label_encoder", None)
        classes = list(encoder.classes_) if encoder is not None else None
        return loaded, "ml_standard", classes

    logger.info("Cargando modelo de Red Neuronal (Keras/TF): %s", path)
    from tensorflow.keras.models import load_model as load_keras_model
    return load_keras_model(path), "deep_learning", _load_label_classes(path)


def _candidate_model_paths(
    models_dir: str, model_name: str, base_filename: str, strategy_name: str | None = None,
) -> list[str]:
    """
    Rutas candidatas en orden de precedencia:
    0) {modelo}_{tf}_{symbol}_{estrategia} exacto (es lo que escribe el entrenamiento),
    1) nombre libre (el usuario ha renombrado el modelo),
    2) formato {modelo}_{timeframe}_{symbol} sin sufijo.
    Las variantes con otro sufijo de estrategia las resuelve load_model (ambigüedad).
    """
    names = [f"{base_filename}_{strategy_name}"] if strategy_name else []
    names += [model_name, base_filename]
    return [os.path.join(models_dir, f"{name}{ext}") for name in names for ext in MODEL_EXTENSIONS]


def _suffix_variants(models_dir: str, base_filename: str) -> list[str]:
    """Modelos del mismo modelo/tf/symbol entrenados con alguna estrategia (orden estable)."""
    return [
        os.path.join(models_dir, fname)
        for fname in sorted(os.listdir(models_dir))
        if fname.startswith(f"{base_filename}_") and fname.endswith(MODEL_EXTENSIONS)
    ]


def _trained_strategy(model: Any, path: str) -> str | None:
    """Estrategia con la que se entrenó el modelo (atributo del .pkl o .metadata.json del .keras)."""
    declared = getattr(model, "strategy_name", None)
    if declared:
        return declared
    metadata_path = os.path.splitext(path)[0] + ".metadata.json"
    if os.path.exists(metadata_path):
        with open(metadata_path, encoding="utf-8") as f:
            return json.load(f).get("strategy_name")
    return None


def _attach_training_metadata(model: Any, path: str) -> None:
    """Expone warmup_candles y train_end_ts también en los .keras (viven en su .metadata.json)."""
    metadata_path = os.path.splitext(path)[0] + ".metadata.json"
    if not os.path.exists(metadata_path):
        return
    with open(metadata_path, encoding="utf-8") as f:
        meta = json.load(f)
    for key in ("warmup_candles", "train_end_ts", "feature_cols"):
        if getattr(model, key, None) is None and meta.get(key) is not None:
            setattr(model, key, meta[key])


def load_model(
    model_name: str, timeframe: str, symbol: str, strategy_name: str | None = None,
) -> tuple[Any, str, list[int] | None]:
    """
    Carga dinámicamente diferentes tipos de modelos basados en su extensión y librería.
    Soporta Scikit-Learn, XGBoost, LightGBM (.pkl) y TensorFlow/Keras (.h5 / .keras).
    Devuelve (modelo, tipo, label_classes) donde label_classes es la lista original
    de clases [-1, 0, 1] leída del .metadata.json (o None si no existe).

    Con strategy_name se prefiere el modelo entrenado con esa estrategia y se aborta si el
    modelo cargado declara otra distinta (sus features no coincidirían).
    """
    models_dir = os.path.join(PROJECT_ROOT, 'models')
    base_filename = f"{model_name}_{timeframe}_{symbol}"

    path = next((p for p in _candidate_model_paths(models_dir, model_name, base_filename, strategy_name)
                 if os.path.exists(p)), None)
    if path is None:
        variants = _suffix_variants(models_dir, base_filename)
        if len(variants) > 1:
            raise ValueError(
                f"Modelo ambiguo para '{base_filename}': {[os.path.basename(v) for v in variants]}. "
                "Indica la estrategia o elimina los que sobren."
            )
        path = variants[0] if variants else None
    if path is None:
        raise FileNotFoundError(
            f"No se encontró ningún modelo para '{model_name}' "
            f"(buscado como {base_filename}[_estrategia], nombre libre y variantes con sufijo). "
            f"Extensiones probadas: {', '.join(MODEL_EXTENSIONS)}"
        )

    verify_model_manifest(path)
    loaded = _load_from_path(path)
    _attach_training_metadata(loaded[0], path)
    trained_with = _trained_strategy(loaded[0], path)
    if strategy_name and trained_with and trained_with != strategy_name:
        raise ValueError(
            f"El modelo {os.path.basename(path)} se entrenó con '{trained_with}' "
            f"pero el motor usa '{strategy_name}': las features no coinciden."
        )
    return loaded

# =========================
# SUBFUNCIONES DE PREDICCIÓN
# =========================

def _prepare_feature_row(
    df_buffer: deque,
    strategy,
    symbol: str,
    model_name: str,
    model=None,
):
    """
    Construye y valida la fila de features para predicción.
    Devuelve el array de features listo para el modelo, o None si hay problema.
    Las columnas son las guardadas con el modelo; si no las trae (modelo antiguo), las de la estrategia.
    """
    df = pd.DataFrame(list(df_buffer))
    df_con_indicadores = strategy.populate_indicators(df.copy())

    saved_cols = getattr(model, "feature_cols", None)
    actual_feature_cols = list(saved_cols) if saved_cols else strategy.get_feature_columns(df_con_indicadores)
    if not actual_feature_cols:
        logger.warning("[%s] La estrategia no devolvio feature columns; se omite predicción.", symbol)
        return None

    missing_cols = [col for col in actual_feature_cols if col not in df_con_indicadores.columns]
    if missing_cols:
        logger.error(
            "[%s] Feature columns faltantes para %s. Esperadas: %s. Faltantes: %s.",
            symbol, model_name, actual_feature_cols, missing_cols,
        )
        return None

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
    )
    if ultima_fila.isna().any(axis=None):
        # Rellenar con 0.0 daría al modelo features que nunca vio al entrenar (train descarta estas filas).
        logger.warning("[%s] Features con NaN/inf (indicadores sin calentar); se omite predicción.", symbol)
        return None
    return ultima_fila.to_numpy(dtype="float32", copy=False)


def _decode(value, label_classes: list[int] | None):
    """Pasa una clase codificada (0..n-1) del modelo al espacio original (-1/0/1)."""
    return int(label_classes[int(value)]) if label_classes is not None else value


def _predict_ml_standard(
    model, x_pred, model_name: str, symbol: str, label_classes: list[int] | None = None,
) -> str | None:
    """Ejecuta predicción con modelos sklearn/XGBoost/LightGBM y devuelve la acción.

    El modelo se entrena con etiquetas codificadas (LabelEncoder); label_classes las devuelve
    al espacio original antes de decidir. Sin label_classes se asume que ya lo están.
    """
    prediccion = _decode(model.predict(x_pred)[0], label_classes)
    clases_modelo = [_decode(c, label_classes) for c in getattr(model, "classes_", [])]
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
    levels: tuple[float, float] | None = None, reason: str = "SIGNAL",
) -> None:
    """Formatea y emite la señal de trading por stdout para que Java la consuma.

    Un BUY lleva stop y objetivo (con ellos dimensiona Java); un SELL, el motivo (SIGNAL, STOP, TARGET).
    """
    signal = {
        "symbol": symbol,
        "action": action,
        "timeframe": timeframe,
        "price": float(new_row["close"]),
        "timestamp": int(new_row["timestamp"]),
        "is_real": is_real,
        "source": f"AI_{model_name.upper()}",
        "reason": reason,
    }
    if levels is not None:
        signal["stop_loss"] = levels[0]
        signal["take_profit"] = levels[1]
    print(build_signal_line(signal), flush=True)
    logger.info("SEÑAL %s ENVIADA: %s para %s a %s", model_name.upper(), action, symbol, signal["price"])


# =========================
# LOOP POR SÍMBOLO
# =========================
MAX_CONSECUTIVE_FAILURES = 5
MIN_WARMUP_CANDLES = 50
BACKFILL_MARGIN = 50


class ModelFailure(RuntimeError):
    """La predicción falla de forma persistente: reconectar el WebSocket no lo arregla."""


def _warmup_candles(strategy) -> int:
    """Velas mínimas antes de predecir: lo que pide la estrategia, con un suelo."""
    return max(strategy.get_warmup_period(), MIN_WARMUP_CANDLES)


def _decide_action(df_buffer: deque, strategy, symbol, model, model_type, model_name, label_classes):
    """Features + predicción (trabajo de CPU, se ejecuta fuera del event loop)."""
    x_pred = _prepare_feature_row(df_buffer, strategy, symbol, model_name, model)
    if x_pred is None:
        return None
    if model_type == "ml_standard":
        return _predict_ml_standard(model, x_pred, model_name, symbol, label_classes)
    if model_type == "deep_learning":
        return _predict_deep_learning(model, x_pred, model_name, symbol, label_classes)
    return None


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
    open_position: dict | None = None,
) -> None:
    clean_symbol = symbol.lower().replace("/", "")
    url = f"wss://stream.binance.com:9443/ws/{clean_symbol}@kline_{timeframe}"

    logger.info("Iniciando AI Stream para %s en %s", symbol, timeframe)
    strategy = load_strategy_by_path(strategy_path, capital, risk_per_trade)
    model, model_type, label_classes = load_model(
        model_name, timeframe, symbol, strategy.get_name())
    min_candles = _warmup_candles(strategy)
    buffer_size = max(max_candles, min_candles + BACKFILL_MARGIN)
    df_buffer: deque = deque(maxlen=buffer_size)
    last_processed_event_id = 0
    consecutive_failures = 0
    retry_delay = 5
    max_retry_delay = 300
    tracker = PositionTracker(open_position)

    while True:
        try:
            async with websockets.connect(url) as ws:
                logger.info("Conectado a WebSocket de Binance para %s", symbol)
                await seed_buffer(df_buffer, symbol, timeframe, buffer_size, fetch_recent_klines)
                async for msg in ws:
                    data = json.loads(msg)
                    k = data.get("k")
                    if not k:
                        continue  # mensajes que no son kline
                    retry_delay = 5  # el backoff solo se reinicia con datos válidos
                    is_candle_closed = k["x"]

                    # Stop y objetivo se vigilan en cada mensaje, también con la vela sin cerrar.
                    protective = tracker.protective_exit(float(k["c"]))
                    if protective is not None:
                        exit_price, reason = protective
                        tracker.close()
                        _emit_signal(symbol, "SELL", timeframe,
                                     {"close": exit_price, "timestamp": int(k["t"])},
                                     model_name, is_real, reason=reason)
                        continue

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
                    upsert_candle(df_buffer, new_row)

                    if len(df_buffer) < min_candles:
                        continue

                    try:
                        action = await asyncio.to_thread(
                            _decide_action, df_buffer, strategy, symbol,
                            model, model_type, model_name, label_classes)
                        consecutive_failures = 0
                    except Exception:
                        # Un fallo del modelo/estrategia no es una caída de red: no reconectar.
                        consecutive_failures += 1
                        logger.exception("Error de predicción en %s (%d/%d)",
                                         symbol, consecutive_failures, MAX_CONSECUTIVE_FAILURES)
                        if consecutive_failures >= MAX_CONSECUTIVE_FAILURES:
                            raise ModelFailure(f"{symbol}: {consecutive_failures} fallos seguidos") from None
                        continue

                    levels = None
                    if action == "BUY":
                        if tracker.is_open:
                            action = None  # ya hay posición: no se reabre ni se cambian sus niveles
                        else:
                            levels = strategy_levels(strategy, new_row["close"], new_row)
                            if levels is None:
                                logger.warning("BUY de %s descartado: la estrategia no dio un stop válido "
                                               "(por debajo de la entrada); sin stop no se puede acotar el riesgo",
                                               symbol)
                                action = None
                    elif action == "SELL" and not tracker.is_open:
                        action = None  # nada que vender

                    if action:
                        if levels is not None:
                            tracker.open(*levels)
                        else:
                            tracker.close()
                        _emit_signal(symbol, action, timeframe, new_row, model_name, is_real, levels)

            logger.warning("Stream de %s cerrado por el servidor; reconectando en %ds", symbol, retry_delay)
        except ModelFailure:
            raise  # termina el proceso con código != 0 para que Java lo supervise
        except Exception:
            logger.exception("Error en loop WS de %s", symbol)

        await asyncio.sleep(retry_delay)
        retry_delay = min(retry_delay * 2, max_retry_delay)

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
            open_positions=payload.get("open_positions") or {},
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
    open_positions: dict | None = None,
) -> None:
    tasks = [
        run_symbol(sym, timeframe, strategy_path, model_name, capital, risk_per_trade, is_real,
                   high_activity_test_mode=high_activity_test_mode,
                   open_position=(open_positions or {}).get(sym))
        for sym in symbols
    ]
    # Heartbeat de liveness: mantiene vivo el canal RT aunque no haya señales.
    tasks.append(emit_heartbeats())
    await asyncio.gather(*tasks)

if __name__ == "__main__":
    main()
