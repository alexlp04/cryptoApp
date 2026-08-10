"""test_engine_fetch.py — Tests del motor de descarga de velas (engine_fetch).

Cubre la mejora de rendimiento que reutiliza una única ``requests.Session`` con
pool de conexiones (keep-alive) en lugar de abrir una conexión TCP+TLS nueva por
request, y verifica que la lógica de reintentos sigue apoyándose en esa sesión.
"""
from __future__ import annotations

import requests

import engine_fetch


class _FakeResp:
    """Respuesta mínima compatible con lo que usa ``_request_with_backoff``."""

    def __init__(self, status_code: int, json_data=None, headers: dict | None = None) -> None:
        self.status_code = status_code
        self._json = json_data if json_data is not None else []
        self.headers = headers or {}

    def json(self):
        return self._json


def test_session_is_shared_and_pooled():
    # Debe existir una única sesión de módulo con un adaptador HTTP con pool
    # dimensionado al número de workers paralelos.
    assert isinstance(engine_fetch._SESSION, requests.Session)
    adapter = engine_fetch._SESSION.get_adapter("https://api.binance.com")
    assert isinstance(adapter, requests.adapters.HTTPAdapter)
    assert adapter._pool_maxsize >= engine_fetch.MAX_PARALLEL_WORKERS


def test_request_uses_shared_session(monkeypatch):
    captured: dict = {}

    def fake_get(url, params=None, timeout=None):
        captured["url"] = url
        captured["params"] = params
        captured["timeout"] = timeout
        return _FakeResp(200, [[1, 2, 3]])

    # Si el código usara requests.get global en vez de la sesión, este patch no
    # se activaría y el test fallaría (o intentaría salir a la red).
    monkeypatch.setattr(engine_fetch._SESSION, "get", fake_get)

    data = engine_fetch._request_with_backoff({"symbol": "BTCUSDT"})

    assert data == [[1, 2, 3]]
    assert captured["url"] == engine_fetch.URL_FETCH
    assert captured["timeout"] == engine_fetch.REQUEST_TIMEOUT_SECONDS
    assert captured["params"] == {"symbol": "BTCUSDT"}


def test_retries_on_server_error_then_succeeds(monkeypatch):
    # No dormir de verdad durante el backoff.
    monkeypatch.setattr(engine_fetch.time, "sleep", lambda *a, **k: None)

    responses = [_FakeResp(500), _FakeResp(200, [[9, 9, 9]])]

    def fake_get(url, params=None, timeout=None):
        return responses.pop(0)

    monkeypatch.setattr(engine_fetch._SESSION, "get", fake_get)

    data = engine_fetch._request_with_backoff({"symbol": "X"}, max_retries=3)

    assert data == [[9, 9, 9]]
    assert responses == []  # se consumieron ambas respuestas (hubo reintento)


def test_returns_none_on_client_error(monkeypatch):
    monkeypatch.setattr(engine_fetch.time, "sleep", lambda *a, **k: None)

    def fake_get(url, params=None, timeout=None):
        return _FakeResp(400, {"code": -1121, "msg": "Invalid symbol."})

    monkeypatch.setattr(engine_fetch._SESSION, "get", fake_get)

    data = engine_fetch._request_with_backoff({"symbol": "NOPE"}, max_retries=2)

    assert data is None
