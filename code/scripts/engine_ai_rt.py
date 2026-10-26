from __future__ import annotations

import asyncio
import json
import logging
import sys
import types
import warnings
from collections import deque
from decimal import Decimal

import numpy as np
import pandas as pd
import websockets
from ipc_protocol import read_request_payload
from model_loader import NEUTRAL, decode_deep_learning, load_model
from risk_management import PositionTracker, signal_actions, strategy_levels
from shared_utils import (
    CODE_DIR,
    build_signal_line,
    emit_heartbeats,
    fetch_recent_klines,
    load_strategy_by_path,
    require_name,
    require_symbol,
    require_timeframe,
    seed_buffer,
    setup_engine_logging,
    upsert_candle,
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

# =========================
# SUBFUNCIONES DE PREDICCIÓN
# =========================
#: Probabilidad mínima de la clase predicha para operar con un modelo binario de scikit-learn.
#: shortcut: umbral único para todos los modelos; si hace falta uno por modelo, guardarlo en su metadata.
BINARY_MIN_PROB = 0.52

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
            if int(prediccion) == 1 and prob_sube >= BINARY_MIN_PROB:
                return "BUY"
            if int(prediccion) == 0 and prob_baja >= BINARY_MIN_PROB:
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

    Decodifica con `decode_deep_learning`, la misma función que usa el backtest. Un modelo de 3 clases
    (-1/0/1) hace hold con 0; uno binario (0/1, o sigmoide) vende con 0.
    """
    raw = model.predict(x_pred, verbose=0)
    label = int(decode_deep_learning(raw, label_classes)[0])
    three_classes = (-1 in label_classes) if label_classes is not None else raw.ndim == 2 and raw.shape[1] > 2
    logger.info("[%s] RED NEURONAL Predice: %d (salida %s)", symbol, label,
                ", ".join(f"{p:.1%}" for p in np.asarray(raw)[0]))
    if label == NEUTRAL:
        return None
    if label == 1:
        return "BUY"
    if label == -1 or (label == 0 and not three_classes):
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
        "price": str(Decimal(str(new_row["close"]))),  # texto: Java lo lee como BigDecimal, como en engine_rt
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


def _indicator_row(df_buffer: deque, strategy):
    """Última fila con los indicadores de la estrategia: sus stops (p. ej. por ATR) los necesitan.
    Trabajo de CPU: se ejecuta fuera del event loop."""
    return strategy.populate_indicators(pd.DataFrame(list(df_buffer)).copy()).iloc[-1]


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
                        exit_action = "COVER" if tracker.side == "short" else "SELL"
                        tracker.close()
                        _emit_signal(symbol, exit_action, timeframe,
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

                    # El modelo predice subida (BUY) o bajada (SELL): según la posición abierta, la subida compra
                    # o cubre un corto, y la bajada vende un largo o abre un corto (si la estrategia los permite).
                    shorts = bool(getattr(strategy, "allow_short", False))
                    actions = signal_actions(tracker.side, action == "BUY", action == "SELL",
                                             shorts and action == "SELL", shorts and action == "BUY")
                    row = None
                    for act in actions:
                        levels = None
                        if act in ("BUY", "SHORT"):
                            if row is None:
                                row = await asyncio.to_thread(_indicator_row, df_buffer, strategy)
                            levels = strategy_levels(strategy, new_row["close"], row, short=act == "SHORT")
                            if levels is None:
                                logger.warning("%s de %s descartado: la estrategia no dio un stop válido "
                                               "(al otro lado de la entrada); sin stop no se puede acotar el riesgo",
                                               act, symbol)
                                continue
                        if levels is not None:
                            tracker.open(*levels, side="short" if act == "SHORT" else "long")
                        else:
                            tracker.close()
                        _emit_signal(symbol, act, timeframe, new_row, model_name, is_real, levels)

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
            symbols=[require_symbol(s) for s in payload["symbols"]],
            timeframe=require_timeframe(payload["timeframe"]),
            strategy_path=payload["strategy_path"],
            model_name=require_name(model_name, "model_name"),
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
