import sys
import json
import time
import os
import logging
from datetime import datetime, timezone, timedelta
import pandas as pd
import requests

# =========================
# CONFIGURACIÓN DE LOGS
# =========================
log_dir = os.path.join(os.getcwd(), "logs")
os.makedirs(log_dir, exist_ok=True)
log_file = os.path.join(log_dir, "engine_fetch.log")

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s [%(levelname)s] %(message)s',
    handlers=[
        logging.FileHandler(log_file, encoding='utf-8')
    ]
)

URL_FETCH = "https://api.binance.com/api/v3/klines"

UPDATE_THRESHOLD = {
    "1m": timedelta(minutes=1),
    "5m": timedelta(minutes=5),
    "15m": timedelta(minutes=15),
    "30m": timedelta(minutes=30),
    "1h": timedelta(hours=1),
    "4h": timedelta(hours=4),
    "1d": timedelta(days=1),
    "1w": timedelta(weeks=1),
    "1M": timedelta(days=30),
}

def obtener_fecha_listado(symbol, timeframe):
    logging.info(f"Buscando fecha de listado original para {symbol}...")
    params = {
        "symbol": symbol, 
        "interval": timeframe,
        "limit": 1,
        "startTime": 0
    }
    try:
        res = requests.get(URL_FETCH, params=params).json()
        if res and isinstance(res, list) and len(res) > 0:
            fecha_ms = res[0][0]
            logging.info(f"Fecha de listado encontrada: {pd.to_datetime(fecha_ms, unit='ms')}")
            return fecha_ms
    except Exception as e:
        logging.error(f"Error buscando fecha de listado para {symbol}: {str(e)}")
    
    logging.warning(f"No se pudo determinar la fecha de listado para {symbol}. Se usará valor por defecto.")
    return None

def obtener_params(symbol, interval, since):
    params = {
        "symbol": symbol,
        "interval": interval,
        "limit": 1000
    }
    if since is not None:
        params["startTime"] = since
    return params

def obtener_datos_binance_streaming(symbol, timeframe, since_binance, max_retries=3):
    """
    Descarga datos de Binance y los envía por stdout inmediatamente en cada lote.
    Esto permite que Java lea y procese mientras Python sigue descargando.
    """
    logging.info(f"Descargando datos desde {pd.to_datetime(since_binance, unit='ms')} para {symbol} ({timeframe}) en modo streaming...")
    
    total_enviadas = 0
    params = obtener_params(symbol, timeframe, since_binance)
    
    while True:
        retries = 0
        data = []
        while retries < max_retries:
            try:
                params["startTime"] = since_binance
                response = requests.get(URL_FETCH, params=params)
                response.raise_for_status()
                data = response.json()
                break
            except Exception as e:
                retries += 1
                logging.warning(f"Intento {retries}/{max_retries} fallido al descargar {symbol}: {str(e)}")
                time.sleep(5)

        if retries == max_retries:
            logging.error(f"No se pudo obtener datos para {symbol} tras {max_retries} intentos. Abortando paginación.")
            break
            
        if isinstance(data, list) and len(data) > 0:
            since_binance = data[-1][0] + 1
            
            # Enviar este lote inmediatamente a stdout (streaming)
            for candle in data:
                json_obj = {
                    "openTime": candle[0],
                    "open": float(candle[1]),
                    "high": float(candle[2]),
                    "low": float(candle[3]),
                    "close": float(candle[4]),
                    "volume": float(candle[5]),
                    "closeTime": candle[6],
                    "quoteVolume": float(candle[7]),
                    "trades": int(candle[8]),
                    "takerBaseVolume": float(candle[9]),
                    "takerQuoteVolume": float(candle[10]),
                    "ignore": int(candle[11])
                }
                
                tsv_line = "{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}".format(
                    json_obj["openTime"],
                    json_obj["open"],
                    json_obj["high"],
                    json_obj["low"],
                    json_obj["close"],
                    json_obj["volume"],
                    json_obj["closeTime"],
                    json_obj["quoteVolume"],
                    json_obj["trades"],
                    json_obj["takerBaseVolume"],
                    json_obj["takerQuoteVolume"],
                    json_obj["ignore"]
                )
                print(tsv_line, flush=True)
                total_enviadas += 1
            
            last_datetime = datetime.fromtimestamp(since_binance / 1000, tz=timezone.utc)
            now = datetime.now(timezone.utc)
            
            if (total_enviadas) % 50000 == 0:
                logging.info(f"Streaming progress: {total_enviadas} velas enviadas. Última: {pd.to_datetime(since_binance, unit='ms')}")
            
            if now - last_datetime < UPDATE_THRESHOLD[timeframe]:
                logging.info(f"Datos actualizados hasta el presente ({last_datetime}). Fin de la descarga.")
                break
            
            time.sleep(0.01)
        else:
            logging.info("Binance no devolvió más datos. Fin de la descarga.")
            break

    return total_enviadas    

def fetch(symbol, timeframe, since_binance=None):
    if since_binance is None or since_binance == "None":
        fecha_listado = obtener_fecha_listado(symbol,timeframe)
        if fecha_listado is not None:
            since_binance = fecha_listado
        else:
            # Timestamp por defecto (Agosto 2017)
            since_binance = 1502928000000
    else:
        since_binance = int(since_binance)

    all_data = obtener_datos_binance(symbol, timeframe, since_binance) 
    
    if not all_data:
        logging.warning(f"No se obtuvieron datos finales para {symbol} en {timeframe}.")
        return []

    logging.info(f"Transformando {len(all_data)} registros al formato JSON de la aplicación...")
    json_data = [
        {
            "openTime": d[0],
            "open": float(d[1]),
            "high": float(d[2]),
            "low": float(d[3]),
            "close": float(d[4]),
            "volume": float(d[5]),
            "closeTime": d[6],
            "quoteVolume": float(d[7]),
            "trades": int(d[8]),
            "takerBaseVolume": float(d[9]),
            "takerQuoteVolume": float(d[10]),
            "ignore": int(d[11]),
            "symbol": symbol,
            "timeInterval": timeframe
        }
        for d in all_data
    ]

    return json_data

if __name__ == "__main__":
    logging.info("=== Starting Fetch Engine (Streaming Mode) ===")
    try:
        symbol = sys.argv[1]        
        timeframe = sys.argv[2]
        since = sys.argv[3] if len(sys.argv) == 4 else None

        logging.info(f"Parameters: Symbol={symbol}, Timeframe={timeframe}, Since={since}")

        if since is None or since == "None":
            fecha_listado = obtener_fecha_listado(symbol, timeframe)
            if fecha_listado is not None:
                since = fecha_listado
            else:
                since = 1502928000000  # Agosto 2017 por defecto
        else:
            since = int(since)

        total_enviadas = obtener_datos_binance_streaming(symbol, timeframe, since)
        
        logging.info(f"Fetch completed. {total_enviadas} candles sent in streaming mode.")
        sys.stdout.flush()
        logging.info("=== Fetch Engine completed successfully ===")
        
    except Exception as e:
        logging.error(f"Critical error in fetch engine: {str(e)}", exc_info=True)
        sys.exit(1)