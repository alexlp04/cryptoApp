"""rt_stream.py — conexión al stream de velas de Binance con reconexión, sembrado y detección de huecos.

Lo comparten el motor RT clásico y el de IA. Se ocupa de lo que falla en la red; las decisiones de trading
quedan en cada motor. Qué cubre:
  - reconexión con backoff (1 s al principio, tope 5 min) que se reinicia al recibir datos válidos,
  - sembrado del buffer por REST al conectar y al reconectar, con las velas que se perdieron entre medias,
  - reintento del sembrado en la siguiente vela si el REST falló (el buffer podría tener un hueco),
  - silencio: un stream conectado que no manda nada en STALE_AFTER_SECONDS se da por muerto y se reconecta,
  - mensajes que no son velas, JSON roto o velas fuera de orden se descartan sin reconectar.
"""
from __future__ import annotations

import asyncio
import json
import logging
from collections.abc import AsyncIterator
from dataclasses import dataclass, field
from typing import Any

import shared_utils
import websockets
from shared_utils import seed_buffer

logger = logging.getLogger(__name__)

INITIAL_RETRY_DELAY = 1.0
MAX_RETRY_DELAY = 300.0
#: Un kline llega cada ~2 s en cualquier par líquido; dos minutos sin nada es un stream muerto.
STALE_AFTER_SECONDS = 120.0
_KLINE_KEYS = ("t", "o", "h", "l", "c", "v", "x")


@dataclass
class KlineEvent:
    """Un mensaje de vela válido. `missed` trae, solo en el primero tras una reconexión, las velas cerradas
    que el motor no vio (para revisar si en ellas saltó el stop o el objetivo de una posición abierta)."""

    data: dict[str, Any]
    k: dict[str, Any]
    missed: list[dict] = field(default_factory=list)


def _parse(msg: str | bytes) -> tuple[dict, dict] | None:
    """(mensaje, kline) si es una vela completa; None si no (errores de Binance, JSON roto, campos que faltan)."""
    try:
        data = json.loads(msg)
    except ValueError:
        logger.warning("Mensaje del stream que no es JSON; se descarta: %.80r", msg)
        return None
    k = data.get("k") if isinstance(data, dict) else None
    if not isinstance(k, dict):
        return None  # respuestas de suscripción, errores del servidor...
    if any(key not in k for key in _KLINE_KEYS):
        logger.warning("Vela incompleta del stream; se descarta: %.80r", k)
        return None
    return data, k


async def kline_events(
    symbol: str, timeframe: str, buffer: Any, seed_limit: int, fetch: Any,
) -> AsyncIterator[KlineEvent]:
    """Genera los mensajes de vela de `symbol` para siempre, reconectando solo y sembrando el buffer."""
    url = f"{shared_utils.WS_BASE_URL}/{symbol.lower().replace('/', '')}@kline_{timeframe}"
    retry_delay = INITIAL_RETRY_DELAY
    last_open_time: int | None = None  # vela del último mensaje; sobrevive a las reconexiones
    reseed_pending = False

    while True:
        try:
            async with websockets.connect(url) as ws:
                logger.info("Conectado a WebSocket de Binance para %s", symbol)
                seeded, missed = await seed_buffer(buffer, symbol, timeframe, seed_limit, fetch)
                reseed_pending = not seeded and last_open_time is not None
                if reseed_pending:
                    logger.error("[%s] Sin sembrar tras reconectar: el buffer puede tener un hueco; "
                                 "se reintenta en la próxima vela y no se opera hasta entonces", symbol)
                messages = ws.__aiter__()
                while True:
                    try:
                        msg = await asyncio.wait_for(messages.__anext__(), STALE_AFTER_SECONDS)
                    except StopAsyncIteration:
                        break  # cierre ordenado del servidor (Binance corta cada 24 h)
                    parsed = _parse(msg)
                    if parsed is None:
                        continue
                    data, k = parsed
                    open_time = int(k["t"])
                    if last_open_time is not None and open_time < last_open_time:
                        continue  # mensaje retrasado de una vela anterior
                    retry_delay = INITIAL_RETRY_DELAY  # el backoff solo se reinicia con datos válidos
                    if reseed_pending and open_time != last_open_time:
                        seeded, missed = await seed_buffer(buffer, symbol, timeframe, seed_limit, fetch)
                        reseed_pending = not seeded
                    last_open_time = open_time
                    yield KlineEvent(data, k, missed)
                    missed = []
            logger.warning("Stream de %s cerrado por el servidor; reconectando en %.0fs", symbol, retry_delay)
        except TimeoutError:
            logger.warning("Stream de %s sin datos en %.0fs; reconectando", symbol, STALE_AFTER_SECONDS)
        except websockets.ConnectionClosed as exc:
            logger.warning("Stream de %s cortado (%s); reconectando en %.0fs", symbol, exc, retry_delay)
        except OSError as exc:
            logger.warning("Sin conexión con Binance para %s (%s); reconectando en %.0fs", symbol, exc, retry_delay)
        except websockets.WebSocketException as exc:
            logger.warning("Error del WebSocket de %s (%s); reconectando en %.0fs", symbol, exc, retry_delay)
        except Exception:
            logger.exception("Error inesperado en el stream de %s; reconectando en %.0fs", symbol, retry_delay)

        await asyncio.sleep(retry_delay)
        retry_delay = min(retry_delay * 2, MAX_RETRY_DELAY)
