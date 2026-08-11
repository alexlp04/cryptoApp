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
    buffer: deque = deque(maxlen=max_candles)

    retry_delay = 5
    max_retry_delay = 300

    while True:
        try:
            async with websockets.connect(url) as ws:
                logger.info("Conectado a WebSocket de Binance para %s", symbol)
                retry_delay = 5  # reset on successful connection
                async for msg in ws:
                    data = json.loads(msg)
                    k = data["k"]

                    # En runtime suele interesar reaccionar antes del cierre de vela.
                    # Si se desea comportamiento clásico de cierre, activar only_closed_candles.
                    if only_closed_candles and not k["x"]:
                        continue

                    # Binance devuelve precios como strings — float para operaciones pandas/numpy.
                    # La clave del precio de cierre original se preserva para emitir la señal
                    # con precisión total (Decimal) sin depender del redondeo de float64.
                    raw_close: str = k["c"]

                    buffer.append({
                        "timestamp": int(k["t"]),
                        "open": float(k["o"]),
                        "high": float(k["h"]),
                        "low": float(k["l"]),
                        "close": float(k["c"]),
                        "volume": float(k["v"]),
                    })

                    min_candles = getattr(strategy, "WARMUP_PERIOD", 20)
                    if len(buffer) < min_candles:
                        continue

                    df = pd.DataFrame(list(buffer))
                    df = strategy.populate_indicators(df)
                    row = df.iloc[-1]

                    action = None
                    if strategy.should_buy(row):
                        action = "BUY"
                    elif strategy.should_sell(row):
                        action = "SELL"

                    if action:
                        signal = _build_rt_signal(symbol, action, timeframe, raw_close, row, is_real)
                        print(build_signal_line(signal), flush=True)
                        logger.info("SEÑAL ENVIADA: %s para %s a precio %s", action, symbol, raw_close)

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
