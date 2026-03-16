from __future__ import annotations

import logging
import os
import sys
import time
from datetime import datetime, timedelta, timezone

import msgpack
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
REQUEST_TIMEOUT_SECONDS = 20
BINANCE_LIMIT = 1000

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
        "limit": BINANCE_LIMIT,
    }
    if since is not None:
        params["startTime"] = since
    return params

def _parse_candle(candle: list, symbol: str, timeframe: str) -> dict:
    # Mantener decimales como string para evitar problemas de precisión/coerción en Java.
    return {
        "openTime": int(candle[0]),
        "open": str(candle[1]),
        "high": str(candle[2]),
        "low": str(candle[3]),
        "close": str(candle[4]),
        "volume": str(candle[5]),
        "closeTime": int(candle[6]),
        "quoteVolume": str(candle[7]),
        "trades": int(candle[8]),
        "takerBaseVolume": str(candle[9]),
        "takerQuoteVolume": str(candle[10]),
        "symbol": symbol,
        "timeInterval": timeframe,
    }


def obtener_datos_binance(symbol, timeframe, since_binance, max_retries=3):
    """Descarga paginada de velas Binance y retorna lista de DTOs serializables."""
    logging.info(
        "Descargando datos desde %s para %s (%s) en modo MessagePack...",
        pd.to_datetime(since_binance, unit="ms"),
        symbol,
        timeframe,
    )

    total = 0
    velas = []
    params = obtener_params(symbol, timeframe, since_binance)

    while True:
        retries = 0
        data = []
        while retries < max_retries:
            try:
                params["startTime"] = since_binance
                response = requests.get(URL_FETCH, params=params, timeout=REQUEST_TIMEOUT_SECONDS)
                response.raise_for_status()
                data = response.json()
                break
            except Exception as exc:
                retries += 1
                logging.warning(
                    "Intento %s/%s fallido al descargar %s: %s",
                    retries,
                    max_retries,
                    symbol,
                    str(exc),
                )
                time.sleep(2)

        if retries == max_retries:
            logging.error(
                "No se pudo obtener datos para %s tras %s intentos. Abortando.",
                symbol,
                max_retries,
            )
            break

        if not isinstance(data, list) or not data:
            logging.info("Binance no devolvió más datos. Fin de la descarga.")
            break

        velas.extend(_parse_candle(candle, symbol, timeframe) for candle in data)
        total += len(data)
        since_binance = int(data[-1][0]) + 1

        if total % 50000 == 0:
            logging.info(
                "Progreso fetch: %s velas acumuladas. Última: %s",
                total,
                pd.to_datetime(since_binance, unit="ms"),
            )

        # Si la última vela está cerca de "ahora", terminamos.
        last_datetime = datetime.fromtimestamp(since_binance / 1000, tz=timezone.utc)
        now = datetime.now(timezone.utc)
        if now - last_datetime < UPDATE_THRESHOLD.get(timeframe, timedelta(minutes=1)):
            logging.info("Datos actualizados hasta el presente (%s). Fin de la descarga.", last_datetime)
            break

        time.sleep(0.01)

    return velas


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

    logging.info("Fetch preparado: %s velas serializables", len(all_data))
    return all_data

if __name__ == "__main__":
    logging.info("=== Starting Fetch Engine (MessagePack Mode) ===")
    try:
        symbol = sys.argv[1]
        timeframe = sys.argv[2]
        since = sys.argv[3] if len(sys.argv) == 4 else None

        logging.info(f"Parameters: Symbol={symbol}, Timeframe={timeframe}, Since={since}")

        velas = fetch(symbol, timeframe, since)
        payload = msgpack.packb(velas, use_bin_type=True)
        sys.stdout.buffer.write(payload)
        sys.stdout.buffer.flush()
        logging.info("Fetch completed. %s velas enviadas en MessagePack.", len(velas))
        logging.info("=== Fetch Engine completed successfully ===")

    except Exception as e:
        logging.error(f"Critical error in fetch engine: {str(e)}", exc_info=True)
        sys.exit(1)