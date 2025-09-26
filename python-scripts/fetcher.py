import sys
import ccxt
import time
from datetime import datetime, timezone
import requests
from datetime import datetime, timedelta


URL_FETCH = "https://api.binance.com/api/v3/klines";

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

def obtener_fecha_listado(symbol):
    params = {
        "symbol": "BTCUSDT",
        "interval": "1d",
        "limit": 1,
        "startTime": 0
    }
    res = requests.get(URL_FETCH, params=params).json()
    if res and isinstance(res, list) and len(res) > 0:
        return res[0][0]
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
    # print(f"📥 Descargando datos desde {pd.to_datetime(since_binance, unit='ms')} para {symbol} ({timeframe})...")
    all_data = []
    params = obtener_params(symbol, timeframe, since_binance)
    while True:
        retries = 0
        while retries < max_retries:
            try:
                params["startTime"] = since_binance
                data = requests.get(URL_FETCH, params=params).json()
                break
            except Exception as e:
                retries += 1
                time.sleep(5)

        if retries == max_retries:
            # print(f"❌ No se pudo obtener datos para {symbol} tras {max_retries} intentos.")
            break
        if isinstance(data, list) and len(data) > 0:
            since_binance = data[-1][0] + 1
        else:
            break
        all_data.extend(data)
        last_datetime = datetime.fromtimestamp(since_binance / 1000, tz=timezone.utc)
        now = datetime.now(timezone.utc)
        if now - last_datetime < UPDATE_THRESHOLD[timeframe]:
                break
        # print(f"🔄 Descargando más datos desde {pd.to_datetime(since_binance, unit='ms')} para {symbol} ({timeframe})...")
        time.sleep(0.01)

    return all_data    

def fetch(symbol, timeframe, since=None):
    if since is None:
        since = obtener_fecha_listado(symbol)
        if since is not None:
            since_binance = since
        else:
            since_binance = 1502928000000

    all_data = obtener_datos_binance(symbol, timeframe, since_binance) 
    if not all_data:
        return []

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
    symbol = sys.argv[0]        # BTCUSDT
    timeframe = sys.argv[1]      # 1D, 1h, etc.
    since = sys.argv[2] if len(sys.argv) > 2 else None  # timestamp en ms o None


    json_data = fetch(symbol, timeframe, since)
    print(json_data)
    sys.stdout.flush()


