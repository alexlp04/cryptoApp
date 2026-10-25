import asyncio
import json
import sys
import types
from collections import deque
from decimal import Decimal

import pandas as pd
import websockets
from ipc_protocol import read_request_payload
from risk_management import PositionTracker, signal_actions, strategy_levels
from shared_utils import (
    CODE_DIR,
    build_signal_line,
    emit_heartbeats,
    fetch_recent_klines,
    load_strategy_by_path,
    require_symbol,
    require_timeframe,
    seed_buffer,
    setup_engine_logging,
    upsert_candle,
)

logger = setup_engine_logging("engine_rt")

# Compatibilidad minima para entornos Windows donde falta modulo posix.
if sys.platform == "win32":
    if "posix" not in sys.modules:
        sys.modules["posix"] = types.ModuleType("posix")

if CODE_DIR not in sys.path:
    sys.path.append(CODE_DIR)


MAX_STRATEGY_ERRORS = 5


class StrategyFailure(RuntimeError):
    """La estrategia falla de forma persistente: no tiene sentido seguir reconectando."""


#: Cuándo se evalúa la estrategia: al cierre de cada vela, a la apertura de la siguiente, o en ambos.
SIGNAL_MODES = ("close", "open", "both")


def _validate_signal_mode(mode: str) -> str:
    if mode not in SIGNAL_MODES:
        raise ValueError(f"signal_mode inválido: {mode!r} (usa {', '.join(SIGNAL_MODES)})")
    return mode


def _should_evaluate(mode: str, candle_closed: bool, new_candle: bool) -> bool:
    """close: mensaje de vela cerrada; open: primer mensaje de una vela nueva; both: cualquiera de los dos."""
    return (mode in ("close", "both") and candle_closed) or (mode in ("open", "both") and new_candle)


def _buffer_size(max_candles: int, warmup: int) -> int:
    """Tamaño del buffer: nunca menor que el calentamiento que exige la estrategia."""
    return max(max_candles, warmup)


def _build_rt_signal(
    symbol: str, action: str, timeframe: str,
    raw_close: str, row: "pd.Series", is_real: bool,
    levels: tuple[float, float] | None = None, reason: str = "SIGNAL",
) -> dict:
    """Construye el diccionario de señal para enviar a Java por stdout.

    Un BUY lleva el stop y el objetivo con los que Java dimensiona la posición; un SELL, el motivo
    (SIGNAL de la estrategia, STOP u TARGET si saltó una orden protectora).
    """
    signal = {
        "symbol": symbol,
        "action": action,
        "timeframe": timeframe,
        "price": str(Decimal(raw_close)),
        "timestamp": int(row["timestamp"]),
        "is_real": is_real,
        "reason": reason,
    }
    if levels is not None:
        signal["stop_loss"] = str(Decimal(str(levels[0])))
        signal["take_profit"] = str(Decimal(str(levels[1])))
    return signal


async def run_symbol(
    symbol: str,
    timeframe: str,
    strategy_path: str,
    capital: float,
    risk_per_trade: float,
    is_real: bool,
    max_candles: int = 100,
    signal_mode: str = "close",
    open_position: dict | None = None,
) -> None:
    """Procesa stream kline de un simbolo y emite senales al runtime Java.

    open_position: stop y objetivo de la posición que Java ya tiene abierta en este símbolo (al
    reanudar tras un reinicio), para no dejarla sin protección.
    """
    _validate_signal_mode(signal_mode)
    clean_symbol = symbol.lower().replace("/", "")
    url = f"wss://stream.binance.com:9443/ws/{clean_symbol}@kline_{timeframe}"

    logger.info("Iniciando stream para %s en %s", symbol, timeframe)
    strategy = load_strategy_by_path(strategy_path, capital, risk_per_trade)
    min_candles = getattr(strategy, "WARMUP_PERIOD", 20)
    buffer: deque = deque(maxlen=_buffer_size(max_candles, min_candles))
    last_signal: tuple[int, str] | None = None
    last_open_time: int | None = None  # vela del último mensaje; sobrevive a las reconexiones

    retry_delay = 5
    max_retry_delay = 300

    consecutive_errors = 0
    tracker = PositionTracker(open_position)

    while True:
        try:
            async with websockets.connect(url) as ws:
                logger.info("Conectado a WebSocket de Binance para %s", symbol)
                # Al conectar y al reconectar: calentamiento al instante y sin las velas perdidas.
                await seed_buffer(buffer, symbol, timeframe, _buffer_size(max_candles, min_candles),
                                  fetch_recent_klines)
                async for msg in ws:
                    k = json.loads(msg).get("k")
                    if not k:
                        continue  # mensajes que no son kline (errores, pings de aplicación)
                    retry_delay = 5  # el backoff solo se reinicia con datos válidos

                    # Todo mensaje actualiza el buffer; signal_mode decide solo cuándo se evalúa.
                    open_time = int(k["t"])
                    # El primer mensaje tras (re)conectar no es una apertura real: se ignora como "nueva vela"
                    new_candle = last_open_time is not None and open_time != last_open_time
                    last_open_time = open_time

                    # Binance devuelve precios como strings — float para operaciones pandas/numpy.
                    # La clave del precio de cierre original se preserva para emitir la señal
                    # con precisión total (Decimal) sin depender del redondeo de float64.
                    raw_close: str = k["c"]

                    upsert_candle(buffer, {
                        "timestamp": int(k["t"]),
                        "open": float(k["o"]),
                        "high": float(k["h"]),
                        "low": float(k["l"]),
                        "close": float(k["c"]),
                        "volume": float(k["v"]),
                    })

                    # Stop y objetivo se vigilan en cada mensaje, no solo al evaluar la estrategia.
                    protective = tracker.protective_exit(float(raw_close))
                    if protective is not None:
                        exit_price, reason = protective
                        exit_action = "COVER" if tracker.side == "short" else "SELL"
                        tracker.close()
                        signal = _build_rt_signal(symbol, exit_action, timeframe, str(exit_price), buffer[-1],
                                                  is_real, reason=reason)
                        last_signal = (int(buffer[-1]["timestamp"]), exit_action)
                        print(build_signal_line(signal), flush=True)
                        logger.info("SEÑAL ENVIADA: %s (%s) para %s a precio %s", exit_action, reason, symbol,
                                    exit_price)
                        continue

                    if len(buffer) < min_candles or not _should_evaluate(signal_mode, bool(k["x"]), new_candle):
                        continue

                    try:
                        df = pd.DataFrame(list(buffer))
                        df = strategy.populate_indicators(df)
                        row = df.iloc[-1]

                        shorts = bool(getattr(strategy, "allow_short", False))
                        actions = signal_actions(
                            tracker.side, bool(strategy.should_buy(row)), bool(strategy.should_sell(row)),
                            shorts and bool(strategy.should_short(row)),
                            shorts and bool(strategy.should_cover(row)),
                        )
                        consecutive_errors = 0
                    except Exception:
                        # Un bug de la estrategia no es una caída de red: reconectar no lo arregla.
                        consecutive_errors += 1
                        logger.exception("Error de estrategia en %s (%d/%d)",
                                         symbol, consecutive_errors, MAX_STRATEGY_ERRORS)
                        if consecutive_errors >= MAX_STRATEGY_ERRORS:
                            raise StrategyFailure(f"{symbol}: {consecutive_errors} errores seguidos") from None
                        continue

                    for action in actions:
                        levels = None
                        if action in ("BUY", "SHORT"):
                            levels = strategy_levels(strategy, float(raw_close), row, short=action == "SHORT")
                            if levels is None:
                                logger.warning("%s de %s descartado: la estrategia no dio un stop válido "
                                               "(al otro lado de la entrada); sin stop no se puede acotar el riesgo",
                                               action, symbol)
                                continue
                        # Una señal por vela y acción aunque lleguen varios ticks de la misma vela.
                        if (int(row["timestamp"]), action) == last_signal:
                            continue
                        last_signal = (int(row["timestamp"]), action)
                        if levels is not None:
                            tracker.open(*levels, side="short" if action == "SHORT" else "long")
                        else:
                            tracker.close()
                        signal = _build_rt_signal(symbol, action, timeframe, raw_close, row, is_real, levels)
                        print(build_signal_line(signal), flush=True)
                        logger.info("SEÑAL ENVIADA: %s para %s a precio %s", action, symbol, raw_close)

            logger.warning("Stream de %s cerrado por el servidor; reconectando en %ds", symbol, retry_delay)
        except StrategyFailure:
            raise  # el proceso debe terminar con código != 0 para que Java lo supervise
        except Exception:
            logger.exception("Error en loop de %s", symbol)

        await asyncio.sleep(retry_delay)
        retry_delay = min(retry_delay * 2, max_retry_delay)  # backoff exponencial



def main() -> None:
    """Punto de entrada: recibe configuracion IPC y arranca tareas async."""
    logger.info("Motor Python RT iniciado. Esperando configuracion de Java...")
    try:
        payload = read_request_payload()
        logger.info("Configuracion recibida para %d simbolos", len(payload.get("symbols", [])))
        
        asyncio.run(run_all(
            symbols=[require_symbol(s) for s in payload["symbols"]],
            timeframe=require_timeframe(payload["timeframe"]),
            strategy_path=payload["strategy_path"],
            capital=payload.get("capital", 1000),
            risk_per_trade=payload.get("risk_per_trade", 0.02),
            is_real=payload.get("is_real", False),
            signal_mode=payload.get("signal_mode", "close"),
            open_positions=payload.get("open_positions") or {},
        ))
    except Exception as e:
        logger.critical("Fallo catastrofico en el motor: %s", str(e), exc_info=True)
        sys.exit(1)

async def run_all(
    symbols: list[str],
    timeframe: str,
    strategy_path: str,
    capital: float,
    risk_per_trade: float,
    is_real: bool,
    signal_mode: str = "close",
    open_positions: dict | None = None,
) -> None:
    """Lanza un task por simbolo y mantiene el proceso vivo mientras haya streams."""
    tasks = [
        run_symbol(
            sym,
            timeframe,
            strategy_path,
            capital,
            risk_per_trade,
            is_real,
            signal_mode=signal_mode,
            open_position=(open_positions or {}).get(sym),
        )
        for sym in symbols
    ]
    # Heartbeat de liveness: mantiene vivo el canal RT aunque no haya señales.
    tasks.append(emit_heartbeats())
    await asyncio.gather(*tasks)

if __name__ == "__main__":
    main()
