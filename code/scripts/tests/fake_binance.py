"""fake_binance.py — Binance de mentira (REST y WebSocket) con fallos a voluntad, para probar los motores de verdad.

REST  : FakeRest sirve respuestas guiadas, una por petición (estado, cabeceras, cuerpo, retraso).
WS    : FakeWs acepta conexiones y ejecuta un guion por conexión: enviar, esperar, callar, cerrar de golpe.
Ambos anotan lo que reciben para que el test compruebe cuántas veces y con qué se les llamó.
"""
from __future__ import annotations

import asyncio
import json
import threading
import time
from dataclasses import dataclass, field
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Self
from urllib.parse import parse_qs, urlparse

from websockets.asyncio.server import serve


@dataclass
class Reply:
    status: int = 200
    body: Any = field(default_factory=list)  # list/dict -> JSON; str/bytes -> tal cual
    headers: dict[str, str] = field(default_factory=dict)
    delay: float = 0.0


BASE = 1_700_000_000_000  # una fecha pasada: todas sus velas están cerradas


def klines(first: int, count: int, step: int = 60_000, lows: dict[int, float] | None = None) -> list[list]:
    """Filas de /klines de las velas first..first+count-1 (índices desde BASE), OHLC a 100 salvo `lows`."""
    rows = []
    for i in range(first, first + count):
        t = BASE + i * step
        low = (lows or {}).get(i, 99)
        rows.append([t, "100", "101", str(low), "100", "1", t + step - 1, "100", 1, "1", "100", "0"])
    return rows


class FakeRest:
    """Servidor HTTP en un hilo. `script` se consume petición a petición; vacío, responde 200 con `fallback`."""

    def __init__(self, script: list[Reply] | None = None, fallback: Reply | None = None) -> None:
        self.script = list(script or [])
        self.fallback = fallback or Reply()
        self.requests: list[dict[str, str]] = []
        self._lock = threading.Lock()
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self) -> None:
                query = {k: v[0] for k, v in parse_qs(urlparse(self.path).query).items()}
                with owner._lock:
                    owner.requests.append(query)
                    reply = owner.script.pop(0) if owner.script else owner.fallback
                if reply.delay:
                    time.sleep(reply.delay)
                body = reply.body if isinstance(reply.body, bytes) else (
                    reply.body.encode() if isinstance(reply.body, str) else json.dumps(reply.body).encode())
                try:
                    self.send_response(reply.status)
                    for name, value in reply.headers.items():
                        self.send_header(name, value)
                    self.send_header("Content-Length", str(len(body)))
                    self.end_headers()
                    self.wfile.write(body)
                except (BrokenPipeError, ConnectionResetError):
                    pass  # el cliente se cansó de esperar (timeout)

            def log_message(self, *args: Any) -> None:
                pass

        self._server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self._thread = threading.Thread(target=self._server.serve_forever, daemon=True)

    @property
    def url(self) -> str:
        return f"http://127.0.0.1:{self._server.server_port}/api/v3/klines"

    def __enter__(self) -> Self:
        self._thread.start()
        return self

    def __exit__(self, *exc: object) -> None:
        self._server.shutdown()
        self._server.server_close()


@dataclass
class Send:
    text: str


@dataclass
class Wait:
    seconds: float


@dataclass
class Hang:
    """Se queda conectado sin enviar nada (stream muerto)."""


@dataclass
class Drop:
    """Corta la conexión sin cierre ordenado (como un corte de red o un reinicio del servidor)."""


@dataclass
class Close:
    """Cierre ordenado del servidor (Binance lo hace cada 24 h)."""

    code: int = 1000


class FakeWs:
    """Servidor WebSocket: cada conexión ejecuta el siguiente guion; sin guiones, se queda callado."""

    def __init__(self, sessions: list[list[Any]]) -> None:
        self.sessions = list(sessions)
        self.connections = 0
        self.paths: list[str] = []
        self._server: Any = None
        self.port = 0

    async def _handler(self, ws: Any) -> None:
        self.connections += 1
        self.paths.append(ws.request.path)
        script = self.sessions.pop(0) if self.sessions else [Hang()]
        for step in script:
            if isinstance(step, Send):
                await ws.send(step.text)
            elif isinstance(step, Wait):
                await asyncio.sleep(step.seconds)
            elif isinstance(step, Hang):
                await ws.wait_closed()  # callado hasta que el cliente se vaya
            elif isinstance(step, Drop):
                ws.transport.abort()
                return
            elif isinstance(step, Close):
                await ws.close(code=step.code)
                return

    async def __aenter__(self) -> Self:
        self._server = await serve(self._handler, "127.0.0.1", 0)
        self.port = self._server.sockets[0].getsockname()[1]
        return self

    async def __aexit__(self, *exc: object) -> None:
        self._server.close()
        await self._server.wait_closed()

    @property
    def base_url(self) -> str:
        return f"ws://127.0.0.1:{self.port}/ws"


def tick(index: int, closed: bool = True, price: float = 100.0, step: int = 60_000, base: int = BASE,
         high: float | None = None, low: float | None = None) -> str:
    """Mensaje de kline de la vela `index` (contigua a las demás: `step` ms entre aperturas)."""
    t = base + index * step
    p = str(price)
    return json.dumps({"e": "kline", "E": t + 1000, "k": {
        "t": t, "T": t + step - 1, "o": p, "h": str(high if high is not None else price),
        "l": str(low if low is not None else price), "c": p, "v": "1", "x": closed}})
