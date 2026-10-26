"""test_engine_fetch.py — Tests del motor de descarga de velas (engine_fetch).

Cubre la mejora de rendimiento que reutiliza una única ``requests.Session`` con
pool de conexiones (keep-alive) en lugar de abrir una conexión TCP+TLS nueva por
request, y verifica que la lógica de reintentos sigue apoyándose en esa sesión.
"""
from __future__ import annotations

import engine_fetch
import requests


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


# ── Robustez de la descarga (#80 #81 #83 #87) ───────────────────────────────

import pytest


class _BadJsonResp(_FakeResp):
    def json(self):
        raise ValueError("no es JSON")


def test_retry_after_con_fecha_http_no_rompe_y_usa_el_valor_por_defecto():
    resp = _FakeResp(429, headers={"Retry-After": "Wed, 21 Oct 2026 07:28:00 GMT"})

    assert engine_fetch._retry_after(resp, 60) == 60


def test_retry_after_numerico_se_acota_al_maximo():
    assert engine_fetch._retry_after(_FakeResp(429, headers={"Retry-After": "99999"}), 60) == engine_fetch.MAX_BACKOFF_SEC


def test_un_429_no_gasta_reintentos_y_el_chunk_acaba_descargandose(monkeypatch):
    # bug #43: tres 429 seguidos con max_retries=2 agotaban el presupuesto y dejaban un hueco de datos
    sleeps: list = []
    monkeypatch.setattr(engine_fetch.time, "sleep", lambda s: sleeps.append(s))
    respuestas = iter([_FakeResp(429), _FakeResp(429), _FakeResp(429), _FakeResp(200, [[1]])])
    monkeypatch.setattr(engine_fetch._SESSION, "get", lambda *a, **k: next(respuestas))

    data = engine_fetch._request_with_backoff({"symbol": "X"}, max_retries=2)

    assert data == [[1]] and len(sleeps) == 3


def test_el_rate_limit_sostenido_acaba_rindiendose(monkeypatch):
    sleeps: list = []
    monkeypatch.setattr(engine_fetch.time, "sleep", lambda s: sleeps.append(s))
    monkeypatch.setattr(engine_fetch, "MAX_THROTTLE_WAITS", 4)
    monkeypatch.setattr(engine_fetch._SESSION, "get", lambda *a, **k: _FakeResp(429))

    assert engine_fetch._request_with_backoff({"symbol": "X"}, max_retries=3) is None

    assert len(sleeps) == 5  # la quinta espera supera el límite de 4: se duerme y ya no se reintenta


def test_la_espera_lleva_jitter_acotado_al_25_por_ciento():
    esperas = {engine_fetch._jitter(100.0) for _ in range(50)}

    assert len(esperas) > 1 and all(100.0 <= e <= 125.0 for e in esperas)


def test_error_http_con_cuerpo_html_devuelve_none_sin_excepcion(monkeypatch):
    monkeypatch.setattr(engine_fetch._SESSION, "get", lambda *a, **k: _BadJsonResp(403))

    assert engine_fetch._request_with_backoff({"symbol": "X"}) is None


def test_excepciones_de_requests_distintas_de_timeout_se_reintentan(monkeypatch):
    monkeypatch.setattr(engine_fetch.time, "sleep", lambda *a, **k: None)
    calls = {"n": 0}

    def fake_get(*a, **k):
        calls["n"] += 1
        if calls["n"] == 1:
            raise requests.exceptions.ChunkedEncodingError("cortado")
        return _FakeResp(200, [[1]])

    monkeypatch.setattr(engine_fetch._SESSION, "get", fake_get)

    assert engine_fetch._request_with_backoff({"symbol": "X"}) == [[1]]


def test_un_chunk_fallido_no_se_confunde_con_uno_vacio(monkeypatch):
    monkeypatch.setattr(engine_fetch, "_request_with_backoff", lambda params: None)
    with pytest.raises(engine_fetch.ChunkFailedError):
        engine_fetch._fetch_chunk(0, 0, 1000, "BTCUSDT", "1m")

    monkeypatch.setattr(engine_fetch, "_request_with_backoff", lambda params: [])
    assert engine_fetch._fetch_chunk(0, 0, 1000, "BTCUSDT", "1m") == (0, [])


def test_un_chunk_fallido_aborta_la_descarga_en_vez_de_dejar_huecos(monkeypatch):
    monkeypatch.setattr(engine_fetch, "_request_with_backoff", lambda params: None)

    with pytest.raises(engine_fetch.ChunkFailedError):
        engine_fetch._descargar_chunks_paralelo([(0, 1), (2, 3)], "BTCUSDT", "1m", workers=2)


def test_la_vela_en_curso_no_se_emite(monkeypatch):
    ahora = int(engine_fetch.datetime.now(engine_fetch.UTC).timestamp() * 1000)
    cerrada = {"openTime": ahora - 120_000, "closeTime": ahora - 60_001}
    abierta = {"openTime": ahora - 30_000, "closeTime": ahora + 29_999}
    emitidas: list = []
    monkeypatch.setattr(engine_fetch, "_descargar_chunks_paralelo", lambda *a, **k: [cerrada, abierta])
    monkeypatch.setattr(engine_fetch, "_emitir_velas_ipc", lambda velas: emitidas.extend(velas) or len(velas))

    total = engine_fetch.obtener_datos_binance("BTCUSDT", "1m", ahora - 600_000)

    assert emitidas == [cerrada] and total == 1


def test_fetch_falla_rapido_si_el_simbolo_no_tiene_fecha_de_listado(monkeypatch):
    monkeypatch.setattr(engine_fetch, "obtener_fecha_listado", lambda s, t: None)
    llamado = []
    monkeypatch.setattr(engine_fetch, "obtener_datos_binance", lambda *a, **k: llamado.append(a) or 0)

    with pytest.raises(ValueError, match="fecha de listado"):
        engine_fetch.fetch("NOEXISTE", "1m")

    assert llamado == []  # no se lanzaron miles de chunks con el default de 2017


def test_since_anterior_al_listado_se_recorta_al_listado(monkeypatch):
    monkeypatch.setattr(engine_fetch, "obtener_fecha_listado", lambda s, t: 5_000)
    desde: list = []
    monkeypatch.setattr(engine_fetch, "obtener_datos_binance", lambda s, t, since: desde.append(since) or 1)

    engine_fetch.fetch("BTCUSDT", "1m", "1000")
    engine_fetch.fetch("BTCUSDT", "1m", "9000")
    engine_fetch.fetch("BTCUSDT", "1m", None)

    assert desde == [5_000, 9_000, 5_000]


def test_respuesta_200_que_no_es_una_lista_se_trata_como_error(monkeypatch):
    monkeypatch.setattr(engine_fetch._SESSION, "get", lambda *a, **k: _FakeResp(200, {"inesperado": 1}))

    assert engine_fetch._request_with_backoff({"symbol": "X"}) is None
