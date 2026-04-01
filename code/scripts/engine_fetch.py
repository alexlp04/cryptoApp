from __future__ import annotations

import logging
import os
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timedelta, timezone
from typing import Optional

import pandas as pd
import requests
from ipc_protocol import write_response

# =========================
# CONFIGURACIÓN DE LOGS
# =========================
log_dir = os.path.join(os.getcwd(), "logs")
os.makedirs(log_dir, exist_ok=True)
log_file = os.path.join(log_dir, "engine_fetch.log")

file_handler = logging.FileHandler(log_file, encoding='utf-8')
file_handler.setLevel(logging.DEBUG)
file_handler.setFormatter(
    logging.Formatter('%(asctime)s [%(levelname)s] %(message)s'))

stream_handler = logging.StreamHandler(sys.stderr)
stream_handler.setLevel(logging.INFO)
stream_handler.setFormatter(logging.Formatter('[%(levelname)s] %(message)s'))

logging.basicConfig(level=logging.DEBUG,
                    handlers=[file_handler, stream_handler])

URL_FETCH = "https://api.binance.com/api/v3/klines"
REQUEST_TIMEOUT_SECONDS = 20
BINANCE_MAX_LIMIT = 1500
MAX_PARALLEL_WORKERS = 5
DEFAULT_RATE_LIMIT_WAIT = 60
MAX_BACKOFF_SEC = 300
# Evita frames IPC excesivamente grandes que pueden cerrar el pipe en el consumidor.
EMIT_CHUNK_SIZE = 2_000

INTERVAL_MS = {
    "1m": 60_000,
    "3m": 180_000,
    "5m": 300_000,
    "15m": 900_000,
    "30m": 1_800_000,
    "1h": 3_600_000,
    "2h": 7_200_000,
    "4h": 14_400_000,
    "6h": 21_600_000,
    "8h": 28_800_000,
    "12h": 43_200_000,
    "1d": 86_400_000,
    "3d": 259_200_000,
    "1w": 604_800_000,
    "1M": 2_592_000_000,
}

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

def _parse_candle(candle: list, symbol: str, timeframe: str) -> dict:
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


def _apply_backoff(backoff: float) -> float:
    time.sleep(min(backoff, MAX_BACKOFF_SEC))
    return min(backoff * 2, MAX_BACKOFF_SEC)


def _handle_response_status(response: requests.Response, attempt: int, max_retries: int,
                            backoff: float) -> tuple[str, float]:
    if response.status_code == 429:
        wait = int(response.headers.get("Retry-After", DEFAULT_RATE_LIMIT_WAIT))
        logging.warning("Rate limit (429). Esperando %ss...", wait)
        time.sleep(wait)
        return "retry", backoff

    if response.status_code == 418:
        wait = int(response.headers.get("Retry-After", MAX_BACKOFF_SEC))
        logging.error("IP baneada temporalmente (418). Esperando %ss...", wait)
        time.sleep(wait)
        return "retry", backoff

    if response.status_code >= 500:
        logging.warning(
            "Error servidor Binance (%s). Intento %s/%s. Backoff %.1fs...",
            response.status_code, attempt, max_retries, backoff)
        return "retry", _apply_backoff(backoff)

    return "continue", backoff


def _request_with_backoff(
    params: dict,
    max_retries: int = 5,
) -> Optional[list]:
    backoff = 2.0

    for attempt in range(1, max_retries + 1):
        try:
            response = requests.get(
                URL_FETCH, params=params, timeout=REQUEST_TIMEOUT_SECONDS)

            status_action, backoff = _handle_response_status(
                response, attempt, max_retries, backoff)
            if status_action == "retry":
                continue

            if response.status_code >= 400:
                data = response.json()
                logging.error(
                    "Error Binance HTTP %s: code=%s msg=%s",
                    response.status_code,
                    data.get("code"), data.get("msg"))
                return None

            data = response.json()

            if isinstance(data, dict) and "code" in data:
                logging.error(
                    "Error en body con HTTP 200: code=%s msg=%s",
                    data.get("code"), data.get("msg"))
                return None

            return data

        except requests.exceptions.Timeout:
            logging.warning(
                "Timeout intento %s/%s. Backoff %.1fs...",
                attempt, max_retries, backoff)
            backoff = _apply_backoff(backoff)

        except requests.exceptions.ConnectionError as exc:
            logging.warning(
                "Error conexión intento %s/%s: %s. Backoff %.1fs...",
                attempt, max_retries, str(exc), backoff)
            backoff = _apply_backoff(backoff)

    logging.error("Agotados %s intentos para params=%s", max_retries, params)
    return None


def _calcular_chunks(
    since_ms: int,
    until_ms: int,
    interval_ms: int,
) -> list[tuple[int, int]]:
    chunk_duration_ms = BINANCE_MAX_LIMIT * interval_ms
    chunks = []
    cursor = since_ms

    while cursor < until_ms:
        chunk_end = min(cursor + chunk_duration_ms - interval_ms, until_ms)
        chunks.append((cursor, chunk_end))
        cursor = chunk_end + interval_ms

    logging.info(
        "Pre-calculados %s chunks de hasta %s velas. "
        "Rango: %s -> %s",
        len(chunks), BINANCE_MAX_LIMIT,
        pd.to_datetime(since_ms, unit='ms'),
        pd.to_datetime(until_ms, unit='ms'))
    return chunks


def _fetch_chunk(
    chunk_index: int,
    start_ms: int,
    end_ms: int,
    symbol: str,
    timeframe: str,
) -> tuple[int, list[dict]]:
    params = {
        "symbol": symbol,
        "interval": timeframe,
        "startTime": start_ms,
        "endTime": end_ms,
        "limit": BINANCE_MAX_LIMIT,
    }

    data = _request_with_backoff(params)

    if data is None or not isinstance(data, list) or not data:
        logging.warning(
            "Chunk %s sin datos (%s -> %s).",
            chunk_index,
            pd.to_datetime(start_ms, unit='ms'),
            pd.to_datetime(end_ms, unit='ms'))
        return chunk_index, []

    velas = [_parse_candle(c, symbol, timeframe) for c in data]
    logging.debug(
        "Chunk %s OK: %s velas (%s -> %s)",
        chunk_index, len(velas),
        pd.to_datetime(start_ms, unit='ms'),
        pd.to_datetime(end_ms, unit='ms'))
    return chunk_index, velas


def obtener_fecha_listado(symbol: str, timeframe: str) -> Optional[int]:
    logging.info("Buscando fecha de listado para %s...", symbol)
    data = _request_with_backoff({
        "symbol": symbol,
        "interval": timeframe,
        "limit": 1,
        "startTime": 0,
    })
    if data and isinstance(data, list):
        fecha_ms = int(data[0][0])
        logging.info("Fecha de listado: %s",
                     pd.to_datetime(fecha_ms, unit='ms'))
        return fecha_ms

    logging.warning(
        "No se pudo obtener fecha de listado para %s. Usando default.", symbol)
    return None


def obtener_datos_binance(
    symbol: str,
    timeframe: str,
    since_ms: int,
    workers: int = MAX_PARALLEL_WORKERS,
) -> int:
    until_ms = int(datetime.now(timezone.utc).timestamp() * 1000)
    interval_ms = INTERVAL_MS.get(timeframe)

    if interval_ms is None:
        logging.error("Timeframe desconocido: %s. No se puede calcular chunks.", timeframe)
        return 0

    chunks = _calcular_chunks(since_ms, until_ms, interval_ms)
    if not chunks:
        logging.warning("Sin chunks que descargar.")
        return 0

    logging.info(
        "Iniciando descarga paralela: %s chunks x hasta %s velas, "
        "%s workers. Symbol=%s tf=%s",
        len(chunks), BINANCE_MAX_LIMIT, workers, symbol, timeframe)

    resultados: dict[int, list[dict]] = {}
    completados = 0

    with ThreadPoolExecutor(max_workers=workers) as executor:
        futures = {
            executor.submit(
                _fetch_chunk, i, start, end, symbol, timeframe
            ): i
            for i, (start, end) in enumerate(chunks)
        }

        for future in as_completed(futures):
            chunk_index, velas = future.result()
            resultados[chunk_index] = velas
            completados += 1

            if completados % 10 == 0 or completados == len(chunks):
                logging.info(
                    "Descarga: %s/%s chunks completados (%s%%)",
                    completados, len(chunks),
                    round(completados / len(chunks) * 100))

    todas_las_velas: list[dict] = []
    for i in range(len(chunks)):
        todas_las_velas.extend(resultados.get(i, []))

    seen: set[int] = set()
    velas_unicas: list[dict] = []
    for vela in todas_las_velas:
        if vela["openTime"] not in seen:
            seen.add(vela["openTime"])
            velas_unicas.append(vela)

    duplicados = len(todas_las_velas) - len(velas_unicas)
    if duplicados > 0:
        logging.info("Eliminados %s duplicados en bordes de chunk.", duplicados)

    total_emitidas = 0
    for i in range(0, len(velas_unicas), EMIT_CHUNK_SIZE):
        chunk_ipc = velas_unicas[i: i + EMIT_CHUNK_SIZE]
        try:
            write_response("FETCH_CHUNK", {"velas": chunk_ipc})
        except BrokenPipeError:
            logging.error(
                "Broken pipe emitiendo chunk IPC (offset=%s, size=%s, emitidas=%s/%s). "
                "El consumidor Java cerró stdout antes de finalizar.",
                i,
                len(chunk_ipc),
                total_emitidas,
                len(velas_unicas),
            )
            raise
        total_emitidas += len(chunk_ipc)

        if total_emitidas % 50_000 == 0:
            logging.info(
                "Emitidas %s/%s velas a Java.",
                total_emitidas, len(velas_unicas))

    logging.info(
        "Descarga y emisión completadas: %s velas únicas.", total_emitidas)
    return total_emitidas


def fetch(
    symbol: str,
    timeframe: str,
    since_binance: Optional[str] = None,
) -> int:
    if since_binance is None or since_binance == "None":
        since_ms = obtener_fecha_listado(symbol, timeframe)
        if since_ms is None:
            since_ms = 1_502_928_000_000
    else:
        since_ms = int(since_binance)

    total = obtener_datos_binance(symbol, timeframe, since_ms)

    if total <= 0:
        logging.warning(
            "No se obtuvieron datos para %s [%s].", symbol, timeframe)

    logging.info("Fetch preparado: %s velas para %s [%s].",
                 total, symbol, timeframe)
    return total

if __name__ == "__main__":
    logging.info("=== Fetch Engine (Paralelo + endTime + IPC chunks) ===")
    try:
        if len(sys.argv) < 3:
            logging.error(
                "Uso: engine_fetch.py <symbol> <timeframe> [since_ms]")
            sys.exit(1)

        symbol = sys.argv[1]
        timeframe = sys.argv[2]
        since = sys.argv[3] if len(sys.argv) >= 4 else None

        logging.info(
            "Parámetros: symbol=%s timeframe=%s since=%s",
            symbol, timeframe, since)

        total_velas = fetch(symbol, timeframe, since)
        write_response("FETCH_RESPONSE", {"total": total_velas})

        logging.info(
            "=== Fetch completado. %s velas enviadas. ===", total_velas)

    except Exception as exc:
        logging.error("Error crítico: %s", str(exc), exc_info=True)
        sys.exit(1)