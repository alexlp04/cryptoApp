"""Tests de la inferencia en tiempo real del engine de IA."""
from __future__ import annotations

import json

import engine_ai_rt
import model_loader
import numpy as np
import pytest
from engine_ai_rt import _predict_deep_learning


def test_numpy_esta_importado_en_el_modulo():
    """Regresión directa del bug #40: el módulo debe exponer `np`.

    Redundante a propósito con los tests de comportamiento: si alguien vuelve a
    mover el import a un scope local, este falla primero y señala la causa exacta
    en lugar de un `NameError` a mitad de una predicción.
    """
    assert hasattr(engine_ai_rt, "np")


class _FakeKerasModel:
    """Doble de un modelo Keras: devuelve una predicción fija con la forma pedida."""

    def __init__(self, raw: np.ndarray) -> None:
        self._raw = raw

    def predict(self, x_pred, verbose=0):
        return self._raw


# ── Rama multiclase (softmax) ────────────────────────────────────────────────

@pytest.mark.parametrize(("probs", "label_classes", "esperado"), [
    ([[0.10, 0.20, 0.70]], [-1, 0, 1], "BUY"),   # argmax=2 → clase 1
    ([[0.70, 0.20, 0.10]], [-1, 0, 1], "SELL"),  # argmax=0 → clase -1
    ([[0.20, 0.70, 0.10]], [-1, 0, 1], None),    # argmax=1 → clase 0 (hold)
])
def test_prediccion_multiclase_decodifica_la_clase(probs, label_classes, esperado):
    """La rama softmax debe decodificar el índice al espacio original de etiquetas.

    Regresión: usaba `np.argmax` sin que el módulo importase numpy, así que
    cualquier modelo de más de una clase reventaba con NameError en producción.
    """
    model = _FakeKerasModel(np.array(probs, dtype="float32"))

    accion = _predict_deep_learning(model, None, "neural_network", "BTCUSDT", label_classes)

    assert accion == esperado


def test_prediccion_multiclase_sin_label_classes_usa_fallback():
    """Sin label_classes, el índice se centra alrededor de 0: {0→-1, 1→0, 2→1}."""
    model = _FakeKerasModel(np.array([[0.10, 0.20, 0.70]], dtype="float32"))

    assert _predict_deep_learning(model, None, "neural_network", "BTCUSDT", None) == "BUY"


# ── Rama binaria (sigmoid) ───────────────────────────────────────────────────

@pytest.mark.parametrize(("prob", "esperado"), [
    (0.90, "BUY"),
    (0.10, "SELL"),
    (0.50, None),   # zona muerta entre 0.45 y 0.55
])
def test_prediccion_binaria_aplica_umbrales(prob, esperado):
    """La rama sigmoid escalar mantiene los umbrales 0.55 / 0.45."""
    model = _FakeKerasModel(np.array([[prob]], dtype="float32"))

    assert _predict_deep_learning(model, None, "neural_network", "BTCUSDT", None) == esperado


# ── ml_standard decodifica las clases codificadas (#59) ─────────────────────

class _FakeSklearnModel:
    """Modelo entrenado sobre etiquetas codificadas 0,1,2 (originales -1,0,1)."""
    classes_ = (0, 1, 2)

    def __init__(self, encoded_pred: int, probs):
        self._pred = encoded_pred
        self._probs = probs

    def predict(self, x):
        return [self._pred]

    def predict_proba(self, x):
        return [self._probs]


@pytest.mark.parametrize("encoded, esperado", [(0, "SELL"), (1, None), (2, "BUY")])
def test_ml_standard_decodifica_clases_codificadas(encoded, esperado):
    from engine_ai_rt import _predict_ml_standard

    model = _FakeSklearnModel(encoded, [0.2, 0.3, 0.5])
    assert _predict_ml_standard(model, None, "xgb", "BTCUSDT", [-1, 0, 1]) == esperado


# ── Features sin NaN→0, backfill REST y fallos del modelo (#72 #96) ──────────

import asyncio
import pathlib
from collections import deque

import shared_utils


class _FeatStrategy:
    def __init__(self, value: float, warmup: int = 1):
        self._value = value
        self._warmup = warmup

    def populate_indicators(self, df):
        df["rsi"] = self._value
        return df

    def get_feature_columns(self, df):
        return ["rsi"]

    def get_warmup_period(self):
        return self._warmup

    def get_name(self):
        return "FeatStrategy"


def _buf(n: int = 3) -> deque:
    return deque({"timestamp": i, "open": 1, "high": 1, "low": 1, "close": 1, "volume": 1} for i in range(n))


def test_features_con_nan_no_se_rellenan_con_cero():
    x = engine_ai_rt._prepare_feature_row(_buf(), _FeatStrategy(float("nan")), "BTCUSDT", "xgb")
    assert x is None


def test_features_validas_se_devuelven_como_float32():
    x = engine_ai_rt._prepare_feature_row(_buf(), _FeatStrategy(55.0), "BTCUSDT", "xgb")
    assert x.shape == (1, 1) and x.dtype == np.float32


def test_fetch_recent_klines_descarta_la_vela_abierta(monkeypatch):
    now_ms = 10_000_000.0
    monkeypatch.setattr(shared_utils.time, "time", lambda: now_ms / 1000)

    class _Resp:
        def raise_for_status(self):
            pass

        def json(self):
            cerrada = [1000, "1", "2", "0.5", "1.5", "10", 1999]
            abierta = [2000, "1", "2", "0.5", "1.5", "10", int(now_ms) + 5000]
            return [cerrada, abierta]

    monkeypatch.setattr(shared_utils.requests, "get", lambda *a, **k: _Resp())

    klines = shared_utils.fetch_recent_klines("BTC/USDT", "1m", 2)

    assert [c["timestamp"] for c in klines] == [1000]
    assert klines[0]["close"] == 1.5


class _FakeWS:
    def __init__(self, messages):
        self._messages = messages

    async def __aenter__(self):
        return self

    async def __aexit__(self, *exc):
        return False

    def __aiter__(self):
        async def gen():
            for m in self._messages:
                yield m
        return gen()


def _closed_kline(ts: int) -> str:
    return json.dumps({"E": ts, "k": {"t": ts, "T": ts + 59, "o": "1", "h": "1", "l": "1",
                                      "c": "1", "v": "1", "x": True}})


def _run_symbol(monkeypatch, strategy, messages, decide, seeded):
    monkeypatch.setattr(engine_ai_rt, "load_strategy_by_path", lambda *a, **k: strategy)
    monkeypatch.setattr(engine_ai_rt, "load_model", lambda *a: (object(), "ml_standard", None))
    monkeypatch.setattr(engine_ai_rt, "_decide_action", decide)
    monkeypatch.setattr(engine_ai_rt, "fetch_recent_klines",
                        lambda s, tf, limit: seeded.append(limit) or [])
    monkeypatch.setattr(engine_ai_rt.websockets, "connect", lambda url: _FakeWS(messages))
    return asyncio.run(engine_ai_rt.run_symbol("BTCUSDT", "1m", "x.py", "xgb", 1000.0, 0.02, False))


def test_fallo_persistente_del_modelo_termina_sin_reconectar(monkeypatch):
    def boom(*a, **k):
        raise ValueError("modelo roto")

    seeded: list = []
    messages = [_closed_kline(i * 100) for i in range(engine_ai_rt.MAX_CONSECUTIVE_FAILURES)]
    # warmup 1 → el suelo MIN_WARMUP_CANDLES (50) exige muchas velas: se siembra el buffer a mano
    monkeypatch.setattr(engine_ai_rt, "MIN_WARMUP_CANDLES", 1)

    with pytest.raises(engine_ai_rt.ModelFailure):
        _run_symbol(monkeypatch, _FeatStrategy(1.0), messages, boom, seeded)

    assert len(seeded) == 1  # una sola conexión: no hubo reconexiones


def test_el_buffer_se_siembra_con_warmup_mas_margen(monkeypatch):
    seeded: list = []
    monkeypatch.setattr(engine_ai_rt, "MIN_WARMUP_CANDLES", 1)

    async def fake_sleep(delay):
        raise KeyboardInterrupt

    monkeypatch.setattr(engine_ai_rt.asyncio, "sleep", fake_sleep)

    with pytest.raises(KeyboardInterrupt):
        _run_symbol(monkeypatch, _FeatStrategy(1.0, warmup=300), [], lambda *a, **k: None, seeded)

    assert seeded == [300 + engine_ai_rt.BACKFILL_MARGIN]


# ── Carga de modelos sin azar y guardado atómico (#93) ───────────────────────

import types

import joblib


def _dump(models_dir, name, strategy):
    shared_utils.save_model(
        str(models_dir / name), lambda tmp: joblib.dump(types.SimpleNamespace(strategy_name=strategy), tmp))


@pytest.fixture
def models_dir(tmp_path, monkeypatch):
    monkeypatch.setattr(model_loader, "PROJECT_ROOT", str(tmp_path))
    d = tmp_path / "models"
    d.mkdir()
    return d


def test_load_model_prefiere_el_modelo_de_la_estrategia_exacta(models_dir):
    _dump(models_dir, "xgb_1h_BTCUSDT_A.pkl", "A")
    _dump(models_dir, "xgb_1h_BTCUSDT_B.pkl", "B")

    model, _, _ = engine_ai_rt.load_model("xgb", "1h", "BTCUSDT", "B")

    assert model.strategy_name == "B"


def test_load_model_ambiguo_sin_estrategia_falla(models_dir):
    _dump(models_dir, "xgb_1h_BTCUSDT_A.pkl", "A")
    _dump(models_dir, "xgb_1h_BTCUSDT_B.pkl", "B")

    with pytest.raises(ValueError, match="ambiguo"):
        engine_ai_rt.load_model("xgb", "1h", "BTCUSDT")


def test_load_model_aborta_si_el_modelo_es_de_otra_estrategia(models_dir):
    _dump(models_dir, "xgb_1h_BTCUSDT_A.pkl", "A")

    with pytest.raises(ValueError, match="entrenó con 'A'"):
        engine_ai_rt.load_model("xgb", "1h", "BTCUSDT", "B")


def test_atomic_save_conserva_el_modelo_anterior_si_falla(tmp_path):
    target = tmp_path / "m.pkl"
    target.write_text("viejo")

    def boom(tmp):
        pathlib.Path(tmp).write_text("a medias")
        raise OSError("disco lleno")

    with pytest.raises(OSError):
        shared_utils.atomic_save(str(target), boom)

    assert target.read_text() == "viejo"
    assert [f.name for f in tmp_path.iterdir()] == ["m.pkl"]  # sin temporales


def test_atomic_save_reemplaza_el_fichero(tmp_path):
    target = tmp_path / "m.keras"
    shared_utils.atomic_save(str(target), lambda tmp: pathlib.Path(tmp).write_text("nuevo"))

    assert target.read_text() == "nuevo"


def test_save_model_escribe_manifiesto_y_load_model_lo_verifica(models_dir):
    path = models_dir / "xgb_1h_BTCUSDT_A.pkl"
    shared_utils.save_model(str(path), lambda tmp: joblib.dump(types.SimpleNamespace(strategy_name="A"), tmp))

    assert (models_dir / "xgb_1h_BTCUSDT_A.manifest.json").exists()
    model, _, _ = engine_ai_rt.load_model("xgb", "1h", "BTCUSDT", "A")
    assert model.strategy_name == "A"


def test_load_model_rechaza_un_modelo_modificado(models_dir):
    path = models_dir / "xgb_1h_BTCUSDT_A.pkl"
    shared_utils.save_model(str(path), lambda tmp: joblib.dump(types.SimpleNamespace(strategy_name="A"), tmp))
    joblib.dump(types.SimpleNamespace(strategy_name="A", malicioso=True), path)  # cambia el contenido

    with pytest.raises(shared_utils.ModelIntegrityError, match="no coincide"):
        engine_ai_rt.load_model("xgb", "1h", "BTCUSDT", "A")


def test_load_model_rechaza_otra_version_de_sklearn(models_dir, monkeypatch):
    path = models_dir / "xgb_1h_BTCUSDT_A.pkl"
    shared_utils.save_model(str(path), lambda tmp: joblib.dump(types.SimpleNamespace(strategy_name="A"), tmp))
    instaladas = shared_utils._installed_versions()
    monkeypatch.setattr(shared_utils, "_installed_versions",
                        lambda: {**instaladas, "scikit-learn": "0.1.0"})

    with pytest.raises(shared_utils.ModelIntegrityError, match="scikit-learn"):
        engine_ai_rt.load_model("xgb", "1h", "BTCUSDT", "A")


def test_un_manifiesto_recalculado_sin_la_clave_no_cuela(models_dir):
    """El ataque que el sha256 solo no frenaba: cambiar el modelo y rehacer el manifiesto."""
    import hashlib
    path = models_dir / "xgb_1h_BTCUSDT_A.pkl"
    shared_utils.save_model(str(path), lambda tmp: joblib.dump(types.SimpleNamespace(strategy_name="A"), tmp))
    joblib.dump(types.SimpleNamespace(strategy_name="A", malicioso=True), path)
    manifest = models_dir / "xgb_1h_BTCUSDT_A.manifest.json"
    data = json.loads(manifest.read_text())
    data["sha256"] = hashlib.sha256(path.read_bytes()).hexdigest()  # el atacante recalcula el hash
    manifest.write_text(json.dumps(data))

    with pytest.raises(shared_utils.ModelIntegrityError, match="firma"):
        engine_ai_rt.load_model("xgb", "1h", "BTCUSDT", "A")


def test_un_modelo_sin_firma_se_rechaza_salvo_que_se_permita(models_dir, monkeypatch):
    path = models_dir / "xgb_1h_BTCUSDT_A.pkl"
    joblib.dump(types.SimpleNamespace(strategy_name="A"), path)  # sin manifiesto

    with pytest.raises(shared_utils.ModelIntegrityError, match="no está firmado"):
        engine_ai_rt.load_model("xgb", "1h", "BTCUSDT", "A")

    monkeypatch.setenv("CRYPTOAPP_ALLOW_UNSIGNED_MODELS", "1")
    assert engine_ai_rt.load_model("xgb", "1h", "BTCUSDT", "A")[0].strategy_name == "A"


def test_otra_clave_no_valida_la_firma(models_dir, monkeypatch):
    path = models_dir / "xgb_1h_BTCUSDT_A.pkl"
    shared_utils.save_model(str(path), lambda tmp: joblib.dump(types.SimpleNamespace(strategy_name="A"), tmp))
    monkeypatch.setenv("CRYPTOAPP_MODEL_KEY", "otra-clave")

    with pytest.raises(shared_utils.ModelIntegrityError, match="firma"):
        engine_ai_rt.load_model("xgb", "1h", "BTCUSDT", "A")


def test_la_clave_se_genera_una_vez_con_permisos_0600(tmp_path, monkeypatch):
    monkeypatch.delenv("CRYPTOAPP_MODEL_KEY")
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path))

    k1 = shared_utils._model_key()
    k2 = shared_utils._model_key()

    clave = tmp_path / "cryptoapp" / "model.key"
    assert k1 == k2 and len(k1) == 64
    assert oct(clave.stat().st_mode & 0o777) == "0o600"


def test_rt_usa_las_columnas_guardadas_con_el_modelo():
    modelo = types.SimpleNamespace(feature_cols=["close"])  # la estrategia diría ["rsi"]

    x = engine_ai_rt._prepare_feature_row(_buf(), _FeatStrategy(55.0), "BTCUSDT", "xgb", modelo)

    assert x.shape == (1, 1) and float(x[0, 0]) == 1.0  # close del buffer, no el rsi=55


# ── Stop y objetivo en el RT con IA (gestión de riesgo) ──────────────────────

class _ConStop(_FeatStrategy):
    def get_stop_loss(self, entry_price, row):
        return entry_price * 0.95

    def get_take_profit(self, entry_price, row):
        return entry_price * 1.10


def _kline(ts: int, price: float, closed: bool) -> str:
    p = str(price)
    return json.dumps({"E": ts, "k": {"t": ts, "T": ts + 59, "o": p, "h": p, "l": p, "c": p, "v": "1", "x": closed}})


def _senales(monkeypatch, capsys, strategy, messages, decisiones):
    monkeypatch.setattr(engine_ai_rt, "MIN_WARMUP_CANDLES", 1)
    iterador = iter(decisiones)

    async def fake_sleep(delay):
        raise KeyboardInterrupt

    monkeypatch.setattr(engine_ai_rt.asyncio, "sleep", fake_sleep)
    with pytest.raises(KeyboardInterrupt):
        _run_symbol(monkeypatch, strategy, messages, lambda *a, **k: next(iterador, None), [])
    return [json.loads(ln.split("\t", 1)[1]) for ln in capsys.readouterr().out.splitlines()
            if ln.startswith("SIGNAL")]


def test_el_buy_de_la_ia_lleva_stop_y_objetivo(monkeypatch, capsys):
    senales = _senales(monkeypatch, capsys, _ConStop(1.0), [_kline(100, 100.0, True)], ["BUY"])

    assert senales[0]["action"] == "BUY"
    assert senales[0]["stop_loss"] == pytest.approx(95.0) and senales[0]["take_profit"] == pytest.approx(110.0)


def test_el_stop_salta_con_la_vela_sin_cerrar(monkeypatch, capsys):
    mensajes = [_kline(100, 100.0, True), _kline(200, 94.0, False)]

    senales = _senales(monkeypatch, capsys, _ConStop(1.0), mensajes, ["BUY"])

    assert [(s["action"], s["reason"]) for s in senales] == [("BUY", "SIGNAL"), ("SELL", "STOP")]


def test_la_ia_sin_stop_valido_no_compra(monkeypatch, capsys):
    senales = _senales(monkeypatch, capsys, _FeatStrategy(1.0), [_kline(100, 100.0, True)], ["BUY"])

    assert senales == []


class _StopPorAtr(_FeatStrategy):
    """Como una estrategia real: el stop sale de una columna de indicador, no de la vela cruda."""

    def populate_indicators(self, df):
        df["rsi"] = 1.0
        df["atr"] = 2.0
        return df

    def get_stop_loss(self, entry_price, row):
        return entry_price - 1.5 * row["atr"]

    def get_take_profit(self, entry_price, row):
        return entry_price + 3.0 * row["atr"]


def test_el_stop_por_atr_usa_la_fila_con_indicadores(monkeypatch, capsys):
    senales = _senales(monkeypatch, capsys, _StopPorAtr(1.0), [_kline(100, 100.0, True)], ["BUY"])

    assert senales[0]["action"] == "BUY"
    assert senales[0]["stop_loss"] == pytest.approx(97.0) and senales[0]["take_profit"] == pytest.approx(106.0)


class _ConStopYCortos(_ConStop):
    allow_short = True

    def get_short_stop_loss(self, entry_price, row):
        return entry_price * 1.05

    def get_short_take_profit(self, entry_price, row):
        return entry_price * 0.90


def test_la_ia_abre_un_corto_con_la_prediccion_de_bajada_y_el_stop_lo_cubre(monkeypatch, capsys):
    mensajes = [_kline(100, 100.0, True), _kline(200, 106.0, False)]

    senales = _senales(monkeypatch, capsys, _ConStopYCortos(1.0), mensajes, ["SELL"])

    assert [(s["action"], s["reason"]) for s in senales] == [("SHORT", "SIGNAL"), ("COVER", "STOP")]
    assert senales[0]["stop_loss"] == pytest.approx(105.0)


def test_la_ia_sin_allow_short_ignora_la_prediccion_de_bajada_sin_posicion(monkeypatch, capsys):
    assert _senales(monkeypatch, capsys, _ConStop(1.0), [_kline(100, 100.0, True)], ["SELL"]) == []


# ── Decodificación compartida con el backtest ───────────────────────────────

def test_decode_deep_learning_cubre_softmax_3_clases_binaria_y_sigmoide():
    decode = model_loader.decode_deep_learning
    tres = np.array([[0.7, 0.2, 0.1], [0.1, 0.8, 0.1], [0.1, 0.1, 0.8]])
    assert decode(tres, None).tolist() == [-1, 0, 1]
    assert decode(tres, [-1, 0, 1]).tolist() == [-1, 0, 1]
    dos = np.array([[0.9, 0.1], [0.2, 0.8]])
    assert decode(dos, None).tolist() == [0, 1]  # sin metadata, 2 columnas ya son 0/1 (antes salía {-1, 0}: nunca compraba)
    assert decode(dos, [0, 1]).tolist() == [0, 1]
    sigmoide = np.array([[0.9], [0.5], [0.1]])
    assert decode(sigmoide, None).tolist() == [1, model_loader.NEUTRAL, 0]


def test_un_softmax_binario_vende_con_la_clase_0_pero_uno_de_3_clases_hace_hold():
    binario = _FakeKerasModel(np.array([[0.9, 0.1]], dtype="float32"))
    assert _predict_deep_learning(binario, None, "nn", "BTCUSDT", [0, 1]) == "SELL"
    tres = _FakeKerasModel(np.array([[0.1, 0.8, 0.1]], dtype="float32"))
    assert _predict_deep_learning(tres, None, "nn", "BTCUSDT", [-1, 0, 1]) is None


def test_la_senal_emite_el_precio_como_texto(capsys):
    engine_ai_rt._emit_signal("BTCUSDT", "BUY", "1h", {"close": 65000.5, "timestamp": 1}, "xgb", False)

    linea = capsys.readouterr().out.strip().split("\t", 1)[1]
    assert json.loads(linea)["price"] == "65000.5"
