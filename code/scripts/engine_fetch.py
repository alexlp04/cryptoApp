from __future__ import annotations

import random
import sys
import time
from collections.abc import Iterator
from concurrent.futures import Future, ThreadPoolExecutor
from datetime import UTC, datetime

import pandas as pd
import requests
from ipc_protocol import write_error, write_response
from requests.adapters import HTTPAdapter
from shared_utils import INTERVAL_MS, require_symbol, require_timeframe, setup_engine_logging

logger = setup_engine_logging("engine_fetch")

URL_FETCH = "https://api.binance.com/api/v3/klines"
REQUEST_TIMEOUT_SECONDS = 20
# Binance klines returns up to 1000 rows per request.
BINANCE_MAX_LIMIT = 1000
MAX_PARALLEL_WORKERS = 5
DEFAULT_RATE_LIMIT_WAIT = 60
MAX_BACKOFF_SEC = 300
#: Esperas seguidas por 429/418 (cada una respeta Retry-After) antes de rendirse con un chunk. No gastan el presupuesto
#: de reintentos por error: bajo rate-limit sostenido el chunk acababa en None y dejaba un hueco de datos.
MAX_THROTTLE_WAITS = 30
# Evita frames IPC excesivamente grandes que pueden cerrar el pipe en el consumidor.
EMIT_CHUNK_SIZE = 2_000


def _build_session() -> requests.Session:
    """Crea una sesión HTTP con keep-alive y pool dimensionado a los workers.

    Reutilizar conexiones evita rehacer el handshake TCP+TLS en cada request.
    Al descargar históricos largos (miles de requests con ThreadPoolExecutor)
    ese handshake domina el tiempo, así que el pool acelera notablemente.
    """
    session = requests.Session()
    adapter = HTTPAdapter(
        pool_connections=MAX_PARALLEL_WORKERS,
        pool_maxsize=MAX_PARALLEL_WORKERS,
    )
    session.mount("https://", adapter)
    return session


# Sesión compartida por todos los workers. requests.Session es segura para
# GETs concurrentes al apoyarse en el PoolManager (thread-safe) de urllib3.
_SESSION: requests.Session = _build_session()


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


def _jitter(wait: float) -> float:
    """Hasta un 25 % más: con 5 workers esperando lo mismo, todos reintentarían a la vez (thundering herd)."""
    return wait * (1 + random.uniform(0, 0.25))


def _apply_backoff(backoff: float) -> float:
    time.sleep(_jitter(min(backoff, MAX_BACKOFF_SEC)))
    return min(backoff * 2, MAX_BACKOFF_SEC)


def _retry_after(response: requests.Response, default: int) -> int:
    """Retry-After en segundos. Según RFC 9110 puede ser una fecha HTTP: no se interpreta, se usa el default."""
    try:
        wait = int(response.headers.get("Retry-After", default))
    except (TypeError, ValueError):
        wait = default
    return max(0, min(wait, MAX_BACKOFF_SEC))


def _handle_response_status(response: requests.Response, attempt: int, max_retries: int,
                            backoff: float) -> tuple[str, float]:
    last_attempt = attempt >= max_retries  # no dormir si ya no habrá otro intento

    if response.status_code == 429:
        wait = _retry_after(response, DEFAULT_RATE_LIMIT_WAIT)
        logger.warning("Rate limit (429). Esperando %ss...", wait)
        time.sleep(_jitter(wait))
        return "throttled", backoff

    if response.status_code == 418:
        wait = _retry_after(response, MAX_BACKOFF_SEC)
        logger.error("IP baneada temporalmente (418). Esperando %ss...", wait)
        time.sleep(_jitter(wait))
        return "throttled", backoff

    if response.status_code >= 500:
        logger.warning(
            "Error servidor Binance (%s). Intento %s/%s. Backoff %.1fs...",
            response.status_code, attempt, max_retries, backoff)
        if last_attempt:
            return "retry", backoff
        return "retry", _apply_backoff(backoff)

    return "continue", backoff


def _json_body(response: requests.Response) -> dict | list | None:
    """Cuerpo JSON o None si no lo es (p. ej. HTML de un WAF/proxy en un 403)."""
    try:
        return response.json()
    except ValueError:
        return None


def _request_with_backoff(
    params: dict,
    max_retries: int = 5,
) -> list | None:
    backoff = 2.0
    attempt = 0
    throttled = 0

    while attempt < max_retries:
        attempt += 1
        try:
            response = _SESSION.get(
                URL_FETCH, params=params, timeout=REQUEST_TIMEOUT_SECONDS)

            status_action, backoff = _handle_response_status(
                response, attempt, max_retries, backoff)
            if status_action == "throttled":
                throttled += 1
                if throttled > MAX_THROTTLE_WAITS:
                    logger.error("Rate limit sostenido: %s esperas seguidas sin respuesta para params=%s",
                                 MAX_THROTTLE_WAITS, params)
                    return None
                attempt -= 1  # el rate-limit no es un fallo de la petición: no gasta reintentos
                continue
            if status_action == "retry":
                continue

            if response.status_code >= 400:
                data = _json_body(response)
                if isinstance(data, dict):
                    logger.error(
                        "Error Binance HTTP %s: code=%s msg=%s",
                        response.status_code, data.get("code"), data.get("msg"))
                else:
                    logger.error("Error HTTP %s con cuerpo no JSON", response.status_code)
                return None

            data = _json_body(response)
            if data is None:
                logger.warning("Respuesta 200 con cuerpo no JSON. Intento %s/%s.", attempt, max_retries)
                if attempt < max_retries:
                    backoff = _apply_backoff(backoff)
                continue

            if isinstance(data, dict) and "code" in data:
                logger.error(
                    "Error en body con HTTP 200: code=%s msg=%s",
                    data.get("code"), data.get("msg"))
                return None

            if not isinstance(data, list):
                logger.error("Respuesta inesperada de Binance (se esperaba una lista): %s", type(data).__name__)
                return None

            return data

        except requests.exceptions.RequestException as exc:
            # Base de Timeout, ConnectionError, ChunkedEncodingError, ContentDecodingError...
            logger.warning(
                "Error de red intento %s/%s (%s): %s. Backoff %.1fs...",
                attempt, max_retries, type(exc).__name__, str(exc), backoff)
            if attempt < max_retries:
                backoff = _apply_backoff(backoff)

    logger.error("Agotados %s intentos para params=%s", max_retries, params)
    return None


class ChunkFailedError(RuntimeError):
    """Un chunk no se pudo descargar: seguir produciría una serie con huecos silenciosos."""


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

    logger.info(
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

    if data is None or not isinstance(data, list):
        # Fallo definitivo (reintentos agotados, 4xx...): no es lo mismo que un chunk vacío.
        raise ChunkFailedError(
            f"Chunk {chunk_index} falló ({pd.to_datetime(start_ms, unit='ms')} -> "
            f"{pd.to_datetime(end_ms, unit='ms')})")

    if not data:
        logger.warning(
            "Chunk %s sin datos (%s -> %s).",
            chunk_index,
            pd.to_datetime(start_ms, unit='ms'),
            pd.to_datetime(end_ms, unit='ms'))
        return chunk_index, []

    velas = [_parse_candle(c, symbol, timeframe) for c in data]
    logger.debug(
        "Chunk %s OK: %s velas (%s -> %s)",
        chunk_index, len(velas),
        pd.to_datetime(start_ms, unit='ms'),
        pd.to_datetime(end_ms, unit='ms'))
    return chunk_index, velas


def obtener_fecha_listado(symbol: str, timeframe: str) -> int | None:
    logger.info("Buscando fecha de listado para %s...", symbol)
    data = _request_with_backoff({
        "symbol": symbol,
        "interval": timeframe,
        "limit": 1,
        "startTime": 0,
    })
    if data and isinstance(data, list):
        fecha_ms = int(data[0][0])
        logger.info("Fecha de listado: %s",
                     pd.to_datetime(fecha_ms, unit='ms'))
        return fecha_ms

    logger.warning(
        "No se pudo obtener fecha de listado para %s. Usando default.", symbol)
    return None


def _descargar_chunks_paralelo(
    chunks: list[tuple[int, int]],
    symbol: str,
    timeframe: str,
    workers: int,
) -> Iterator[list[dict]]:
    """Genera las velas de cada chunk en orden, conforme se completan.

    Solo hay en vuelo `2 * workers` chunks: la memoria queda acotada y Java puede insertar mientras se
    sigue descargando. Los chunks son contiguos y disjuntos (`_calcular_chunks`), así que no hace falta
    deduplicar.
    """
    pending: dict[int, Future] = {}
    submitted = 0
    with ThreadPoolExecutor(max_workers=workers) as executor:
        try:
            for emit_index in range(len(chunks)):
                while submitted < len(chunks) and submitted < emit_index + 2 * workers:
                    start, end = chunks[submitted]
                    pending[submitted] = executor.submit(_fetch_chunk, submitted, start, end, symbol, timeframe)
                    submitted += 1
                yield pending.pop(emit_index).result()[1]
                if (emit_index + 1) % 10 == 0 or emit_index + 1 == len(chunks):
                    logger.info("Progreso: %s/%s chunks (%s%%).", emit_index + 1, len(chunks),
                                round((emit_index + 1) / len(chunks) * 100))
        except BaseException:
            # Sin cancelar, el with espera a TODAS las futuras pendientes y el proceso seguía
            # descargando minutos después del fallo (o de que el consumidor abandonase).
            executor.shutdown(wait=False, cancel_futures=True)
            raise


def _emitir_velas_ipc(velas: list[dict]) -> int:
    """Emite las velas a Java en frames IPC de EMIT_CHUNK_SIZE y devuelve cuántas emitió."""
    for i in range(0, len(velas), EMIT_CHUNK_SIZE):
        try:
            write_response("FETCH_CHUNK", {"velas": velas[i: i + EMIT_CHUNK_SIZE]})
        except BrokenPipeError:
            logger.error("Broken pipe emitiendo a Java (offset=%s/%s): el consumidor cerró stdout antes de acabar.",
                         i, len(velas))
            raise
    return len(velas)


def obtener_datos_binance(
    symbol: str,
    timeframe: str,
    since_ms: int,
    workers: int = MAX_PARALLEL_WORKERS,
) -> int:
    until_ms = int(datetime.now(UTC).timestamp() * 1000)
    interval_ms = INTERVAL_MS.get(timeframe)

    if interval_ms is None:
        logger.error("Timeframe desconocido: %s. No se puede calcular chunks.", timeframe)
        return 0

    chunks = _calcular_chunks(since_ms, until_ms, interval_ms)
    if not chunks:
        logger.warning("Sin chunks que descargar.")
        return 0

    logger.info(
        "Iniciando descarga paralela: %s chunks x hasta %s velas, "
        "%s workers. Symbol=%s tf=%s",
        len(chunks), BINANCE_MAX_LIMIT, workers, symbol, timeframe,
    )

    total_emitidas = 0
    for velas in _descargar_chunks_paralelo(chunks, symbol, timeframe, workers):
        # La API incluye la vela en curso (closeTime futuro): persistirla dejaba OHLCV parcial para siempre.
        total_emitidas += _emitir_velas_ipc([v for v in velas if v["closeTime"] < until_ms])

    logger.info("Descarga y emisión completadas: %s velas.", total_emitidas)
    return total_emitidas


def fetch(
    symbol: str,
    timeframe: str,
    since_binance: str | None = None,
) -> int:
    # Siempre se consulta el listado (1 petición): sirve de fail-fast si el símbolo no existe y
    # evita miles de peticiones vacías si se pide una ventana anterior al listado del par.
    listing_ms = obtener_fecha_listado(symbol, timeframe)
    if listing_ms is None:
        raise ValueError(
            f"No se pudo obtener la fecha de listado de {symbol} [{timeframe}]: "
            "símbolo inexistente o Binance no responde.")

    if since_binance is None:
        since_ms = listing_ms
    else:
        since_ms = max(int(since_binance), listing_ms)

    total = obtener_datos_binance(symbol, timeframe, since_ms)

    if total <= 0:
        logger.warning(
            "No se obtuvieron datos para %s [%s].", symbol, timeframe)

    logger.info("Fetch preparado: %s velas para %s [%s].",
                 total, symbol, timeframe)
    return total

if __name__ == "__main__":
    logger.info("=== Fetch Engine (Paralelo + endTime + IPC chunks) ===")
    try:
        if len(sys.argv) < 3:
            logger.error(
                "Uso: engine_fetch.py <symbol> <timeframe> [since_ms]")
            sys.exit(1)

        symbol = require_symbol(sys.argv[1])
        timeframe = require_timeframe(sys.argv[2])
        since = sys.argv[3] if len(sys.argv) >= 4 else None

        logger.info(
            "Parámetros: symbol=%s timeframe=%s since=%s",
            symbol, timeframe, since)

        total_velas = fetch(symbol, timeframe, since)
        write_response("FETCH_RESPONSE", {"total": total_velas})

        logger.info(
            "=== Fetch completado. %s velas enviadas. ===", total_velas)

    except Exception as exc:
        logger.exception("Error crítico")
        write_error("ERROR", f"Fetch fallido: {exc}")
        sys.exit(1)
