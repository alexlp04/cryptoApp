import asyncio
import json
import sys
import types
from collections import deque
from decimal import Decimal

import pandas as pd
import websockets
from ipc_protocol import read_request_payload
from shared_utils import (
    CODE_DIR,
    build_signal_line,
    emit_heartbeats,
    load_strategy_by_path,
    setup_engine_logging,
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


def _buffer_size(max_candles: int, warmup: int) -> int:
    """Tamaño del buffer: nunca menor que el calentamiento que exige la estrategia."""
    return max(max_candles, warmup)


def _upsert_candle(buffer: deque, candle: dict) -> None:
    """Los ticks de la vela abierta comparten open_time: sustituyen a la última, no se añaden."""
    if buffer and buffer[-1]["timestamp"] == candle["timestamp"]:
        buffer[-1] = candle
    else:
        buffer.append(candle)


def _build_rt_signal(
    symbol: str, action: str, timeframe: str,
    raw_close: str, row: "pd.Series", is_real: bool,
) -> dict:
    """Construye el diccionario de señal para enviar a Java por stdout."""
    return {
        "symbol": symbol,
        "action": action,
        "timeframe": timeframe,
        "price": str(Decimal(raw_close)),
        "timestamp": int(row["timestamp"]),
        "is_real": is_real,
    }


async def run_symbol(
    symbol: str,
    timeframe: str,
    strategy_path: str,
    capital: float,
    risk_per_trade: float,
    is_real: bool,
    max_candles: int = 100,
    only_closed_candles: bool = False,
) -> None:
    """Procesa stream kline de un simbolo y emite senales al runtime Java."""
    clean_symbol = symbol.lower().replace("/", "")
    url = f"wss://stream.binance.com:9443/ws/{clean_symbol}@kline_{timeframe}"

    logger.info("Iniciando stream para %s en %s", symbol, timeframe)
    strategy = load_strategy_by_path(strategy_path, capital, risk_per_trade)
    min_candles = getattr(strategy, "WARMUP_PERIOD", 20)
    buffer: deque = deque(maxlen=_buffer_size(max_candles, min_candles))
    last_signal: tuple[int, str] | None = None

    retry_delay = 5
    max_retry_delay = 300

    consecutive_errors = 0

    while True:
        try:
            async with websockets.connect(url) as ws:
                logger.info("Conectado a WebSocket de Binance para %s", symbol)
                async for msg in ws:
                    k = json.loads(msg).get("k")
                    if not k:
                        continue  # mensajes que no son kline (errores, pings de aplicación)
                    retry_delay = 5  # el backoff solo se reinicia con datos válidos

                    # En runtime suele interesar reaccionar antes del cierre de vela.
                    # Si se desea comportamiento clásico de cierre, activar only_closed_candles.
                    if only_closed_candles and not k["x"]:
                        continue

                    # Binance devuelve precios como strings — float para operaciones pandas/numpy.
                    # La clave del precio de cierre original se preserva para emitir la señal
                    # con precisión total (Decimal) sin depender del redondeo de float64.
                    raw_close: str = k["c"]

                    _upsert_candle(buffer, {
                        "timestamp": int(k["t"]),
                        "open": float(k["o"]),
                        "high": float(k["h"]),
                        "low": float(k["l"]),
                        "close": float(k["c"]),
                        "volume": float(k["v"]),
                    })

                    if len(buffer) < min_candles:
                        continue

                    try:
                        df = pd.DataFrame(list(buffer))
                        df = strategy.populate_indicators(df)
                        row = df.iloc[-1]

                        action = None
                        if strategy.should_buy(row):
                            action = "BUY"
                        elif strategy.should_sell(row):
                            action = "SELL"
                        consecutive_errors = 0
                    except Exception:
                        # Un bug de la estrategia no es una caída de red: reconectar no lo arregla.
                        consecutive_errors += 1
                        logger.exception("Error de estrategia en %s (%d/%d)",
                                         symbol, consecutive_errors, MAX_STRATEGY_ERRORS)
                        if consecutive_errors >= MAX_STRATEGY_ERRORS:
                            raise StrategyFailure(f"{symbol}: {consecutive_errors} errores seguidos") from None
                        continue

                    # Una señal por vela y acción aunque lleguen varios ticks de la misma vela.
                    if action and (int(row["timestamp"]), action) != last_signal:
                        last_signal = (int(row["timestamp"]), action)
                        signal = _build_rt_signal(symbol, action, timeframe, raw_close, row, is_real)
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
            symbols=payload["symbols"],
            timeframe=payload["timeframe"],
            strategy_path=payload["strategy_path"],
            capital=payload.get("capital", 1000),
            risk_per_trade=payload.get("risk_per_trade", 0.02),
            is_real=payload.get("is_real", False),
            only_closed_candles=payload.get("only_closed_candles", False),
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
    only_closed_candles: bool = False,
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
            only_closed_candles=only_closed_candles,
        )
        for sym in symbols
    ]
    # Heartbeat de liveness: mantiene vivo el canal RT aunque no haya señales.
    tasks.append(emit_heartbeats())
    await asyncio.gather(*tasks)

if __name__ == "__main__":
    main()
