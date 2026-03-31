import asyncio
import json
import sys
import importlib.util
import pandas as pd
import websockets
import os
import types
import logging # 1. Importar logging
from datetime import datetime
from ipc_protocol import read_request_payload

# =========================
# CONFIGURACIÓN DE LOGS
# =========================
# Creamos una carpeta para logs si no existe
current_dir = os.path.dirname(os.path.abspath(__file__))
project_root = os.path.dirname(current_dir)
log_dir = os.path.join(project_root, "logs")
os.makedirs(log_dir, exist_ok=True)
log_file = os.path.join(log_dir, "engine_rt.log")

# Logger a archivo (para debugging offline)
file_handler = logging.FileHandler(log_file, encoding='utf-8')
file_handler.setLevel(logging.DEBUG)
file_handler.setFormatter(logging.Formatter('%(asctime)s [%(levelname)s] %(message)s'))

# Logger a stdout (para que Java vea el progreso)
stream_handler = logging.StreamHandler(sys.stdout)
stream_handler.setLevel(logging.INFO)
stream_handler.setFormatter(logging.Formatter('[%(levelname)s] %(message)s'))

logging.basicConfig(
    level=logging.DEBUG,
    handlers=[file_handler, stream_handler]
)

# Alias para logging.info → stdout
log = logging.getLogger(__name__)

# ==========================================
# 1. PARCHE DE COMPATIBILIDAD (Windows + Py 3.13)
# ==========================================
if sys.platform == "win32":
    if "posix" not in sys.modules:
        sys.modules["posix"] = types.ModuleType("posix")

# Configuración de rutas para encontrar BaseStrategy
current_dir = os.path.dirname(os.path.abspath(__file__))
project_root = os.path.dirname(current_dir)
if project_root not in sys.path:
    sys.path.append(project_root)

from strategies.BaseStrategy import BaseStrategy

# =========================
# 2. CARGA DE ESTRATEGIA
# =========================
def load_strategy(path, capital=1000, risk_per_trade=0.02):
    try:
        logging.info(f"Cargando estrategia desde: {path}")
        spec = importlib.util.spec_from_file_location("user_strategy", path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)

        for obj in module.__dict__.values():
            if isinstance(obj, type) and issubclass(obj, BaseStrategy) and obj is not BaseStrategy:
                return obj(capital=capital, risk_per_trade=risk_per_trade)
        raise Exception("No se encontró una clase válida en el archivo.")
    except Exception as e:
        logging.error(f"Error crítico cargando estrategia: {str(e)}", exc_info=True)
        sys.exit(1)

# =========================
# 3. LOOP POR SÍMBOLO
# =========================
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
    clean_symbol = symbol.lower().replace("/", "")
    url = f"wss://stream.binance.com:9443/ws/{clean_symbol}@kline_{timeframe}"

    logging.info(f"Iniciando stream para {symbol} en {timeframe}")
    strategy = load_strategy(strategy_path, capital, risk_per_trade)
    df = pd.DataFrame(columns=["timestamp", "close", "open", "high", "low", "volume"])

    while True:
        try:
            async with websockets.connect(url) as ws:
                logging.info(f"Conectado a WebSocket de Binance para {symbol}")
                async for msg in ws:
                    data = json.loads(msg)
                    k = data["k"]

                    # En runtime suele interesar reaccionar antes del cierre de vela.
                    # Si se desea comportamiento clásico de cierre, activar only_closed_candles.
                    if only_closed_candles and not k["x"]:
                        continue

                    new_row = pd.DataFrame([{
                        "timestamp": int(k["t"]),
                        "open": float(k["o"]),
                        "high": float(k["h"]),
                        "low": float(k["l"]),
                        "close": float(k["c"]),
                        "volume": float(k["v"])
                    }])
                    
                    df = pd.concat([df, new_row], ignore_index=True)
                    if len(df) > max_candles:
                        df = df.iloc[-max_candles:]

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
                            "price": float(row["close"]),
                            "timestamp": int(row["timestamp"]),
                            "is_real": is_real
                        }
                        # Formato único runtime para Java.
                        print(f"SIGNAL\t{json.dumps(signal)}", flush=True)
                        logging.info(f"SEÑAL ENVIADA: {action} para {symbol} a precio {row['close']}")

        except Exception as e:
            logging.error(f"Error en loop de {symbol}: {str(e)}", exc_info=True)
            await asyncio.sleep(5) 

# =========================
# 5. MAIN
# =========================
def main():
    logging.info("Motor Python RT iniciado. Esperando configuración de Java...")
    try:
        payload = read_request_payload()
        logging.info(f"Configuración recibida: {payload}")
        
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
        logging.critical(f"Fallo catastrófico en el motor: {str(e)}", exc_info=True)
        sys.exit(1)

async def run_all(symbols, timeframe, strategy_path, capital, risk_per_trade, is_real, only_closed_candles=False):
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