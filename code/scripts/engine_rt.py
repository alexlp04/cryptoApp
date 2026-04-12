import asyncio
import json
import sys
import pandas as pd
import websockets
import os
import types
import logging
from collections import deque
from decimal import Decimal
from ipc_protocol import read_request_payload
from shared_utils import load_strategy_by_path

# Logging dual: archivo para diagnostico y stdout para que Java consuma eventos.
# current_dir  = .../code/scripts/ ; code_dir = .../code/ ; project_root = raíz del proyecto
current_dir = os.path.dirname(os.path.abspath(__file__))
code_dir = os.path.dirname(current_dir)
project_root = os.path.dirname(code_dir)
log_dir = os.path.join(project_root, "logs")
os.makedirs(log_dir, exist_ok=True)
log_file = os.path.join(log_dir, "engine_rt.log")

file_handler = logging.FileHandler(log_file, encoding='utf-8')
file_handler.setLevel(logging.DEBUG)
file_handler.setFormatter(logging.Formatter('%(asctime)s [%(levelname)s] %(message)s'))

stream_handler = logging.StreamHandler(sys.stderr)
stream_handler.setLevel(logging.INFO)
stream_handler.setFormatter(logging.Formatter('[%(levelname)s] %(message)s'))

logging.basicConfig(
    level=logging.DEBUG,
    handlers=[file_handler, stream_handler]
)

logger = logging.getLogger(__name__)

# Compatibilidad minima para entornos Windows donde falta modulo posix.
if sys.platform == "win32":
    if "posix" not in sys.modules:
        sys.modules["posix"] = types.ModuleType("posix")

if code_dir not in sys.path:
    sys.path.append(code_dir)

async def run_symbol(
    symbol,
    timeframe,
    strategy_path,
    capital,
    risk_per_trade,
    is_real,
    max_candles=100,
    only_closed_candles=False,
):
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

                    if len(buffer) < 2:
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
                        signal = {
                            "symbol": symbol,
                            "action": action,
                            "timeframe": timeframe,
                            "price": str(Decimal(raw_close)),  # precisión total desde string Binance
                            "timestamp": int(row["timestamp"]),
                            "is_real": is_real
                        }
                        # Formato unico de intercambio con el runtime Java.
                        print("SIGNAL\t" + json.dumps(signal), flush=True)
                        logger.info("SEÑAL ENVIADA: %s para %s a precio %s", action, symbol, raw_close)

        except Exception as e:
            logger.error("Error en loop de %s: %s", symbol, str(e), exc_info=True)
            await asyncio.sleep(retry_delay)
            retry_delay = min(retry_delay * 2, max_retry_delay)  # backoff exponencial

def main():
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

async def run_all(symbols, timeframe, strategy_path, capital, risk_per_trade, is_real, only_closed_candles=False):
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
    await asyncio.gather(*tasks)

if __name__ == "__main__":
    main()