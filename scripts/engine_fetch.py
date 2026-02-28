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
log_file = os.path.join(log_dir, f"engine_fetch_{datetime.now().strftime('%Y%m%d')}.log")

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

def obtener_datos_binance(symbol, timeframe, since_binance, max_retries=3):
    logging.info(f"📥 Descargando datos desde {pd.to_datetime(since_binance, unit='ms')} para {symbol} ({timeframe})...")
    all_data = []
    params = obtener_params(symbol, timeframe, since_binance)
    
    while True:
        retries = 0
        data = []
        while retries < max_retries:
            try:
                params["startTime"] = since_binance
                response = requests.get(URL_FETCH, params=params)
                response.raise_for_status() # Asegura que si es un error 4XX o 5XX salte al except
                data = response.json()
                break
            except Exception as e:
                retries += 1
                logging.warning(f"Intento {retries}/{max_retries} fallido al descargar {symbol}: {str(e)}")
                time.sleep(5)

        if retries == max_retries:
            logging.error(f"❌ No se pudo obtener datos para {symbol} tras {max_retries} intentos. Abortando paginación.")
            break
            
        if isinstance(data, list) and len(data) > 0:
            since_binance = data[-1][0] + 1
            all_data.extend(data)
            
            last_datetime = datetime.fromtimestamp(since_binance / 1000, tz=timezone.utc)
            now = datetime.now(timezone.utc)
            
            if now - last_datetime < UPDATE_THRESHOLD[timeframe]:
                logging.info(f"Datos actualizados hasta el presente ({last_datetime}). Fin de la descarga.")
                break
            
            logging.info(f"🔄 Progreso: Descargadas {len(all_data)} velas. Siguiente lote desde {pd.to_datetime(since_binance, unit='ms')}...")
            time.sleep(0.01)
        else:
            logging.info("Binance no devolvió más datos. Fin de la descarga.")
            break

    return all_data    

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
            "open_time": d[0],
            "open": float(d[1]),
            "high": float(d[2]),
            "low": float(d[3]),
            "close": float(d[4]),
            "volume": float(d[5]),
            "close_time": d[6],
            "quote_volume": float(d[7]),
            "trades": int(d[8]),
            "taker_base_volume": float(d[9]),
            "taker_quote_volume": float(d[10]),
            "ignore": int(d[11]),
            "symbol": symbol,
            "time_interval": timeframe
        }
        for d in all_data
    ]

    return json_data

if __name__ == "__main__":
    logging.info("=== Arrancando Motor de Descarga (Fetch) ===")
    try:
        symbol = sys.argv[1]        
        timeframe = sys.argv[2]
        since = sys.argv[3] if len(sys.argv) == 4 else None

        logging.info(f"Parámetros recibidos -> Symbol: {symbol}, Timeframe: {timeframe}, Since: {since}")

        json_data = fetch(symbol, timeframe, since)
        total_velas = len(json_data)
        
        logging.info(f"Descarga finalizada. {total_velas} velas en memoria. Preparando envío por lotes.")
        
        if total_velas == 0:
            print("[]")
            sys.stdout.flush()
            sys.exit(0)

        # --- SISTEMA DE LOTES (BATCHING) ---
        BATCH_SIZE = 100000 # Tamaño del lote (puedes ajustarlo si Java sigue quejándose)
        
        for i in range(0, total_velas, BATCH_SIZE):
            lote = json_data[i:i + BATCH_SIZE]
            # Imprimimos el lote como un array JSON en una sola línea.
            # Java leerá esta línea, guardará el lote en BD, y pasará a la siguiente.
            print(json.dumps(lote))
            sys.stdout.flush()
            logging.info(f"Enviado lote de la vela {i} a la {i + len(lote)}.")

        logging.info("=== Proceso finalizado correctamente. Todos los lotes enviados ===")
        
    except Exception as e:
        logging.error(f"Fallo crítico en el script de fetch: {str(e)}", exc_info=True)
        # Imprimimos un array vacío para que Java no se quede colgado
        print("[]") 
        sys.exit(1)