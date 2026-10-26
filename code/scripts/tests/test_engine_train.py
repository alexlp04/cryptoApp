"""Tests del entrypoint de entrenamiento (engine_train.main) sobre un dataset sintético."""
from __future__ import annotations

import json

import engine_train
import numpy as np
import pandas as pd
import pytest
import shared_utils


def _dataset(n: int = 700) -> list[dict]:
    rng = np.random.default_rng(7)
    close = 40_000.0 * np.exp(np.cumsum(rng.normal(0, 0.004, n)))
    return pd.DataFrame({
        "timestamp": 1_700_000_000_000 + np.arange(n) * 3_600_000,
        "open": close * 0.999, "high": close * 1.004, "low": close * 0.996,
        "close": close, "volume": rng.uniform(100, 500, n),
    }).to_dict("records")


def _payload(**override) -> dict:
    return {"model_type": "random_forest", "symbol": "BTCUSDT", "timeframe": "1h",
            "strategy_name": "StressTestStrategy", "warmup_candles": 30, "dataset": _dataset(),
            "hyperparameters": {"n_estimators": 10, "max_depth": 3}, **override}


@pytest.fixture
def entrenar(tmp_path, monkeypatch):
    """Ejecuta main() con un payload dado en una carpeta de proyecto temporal y devuelve (respuesta, error)."""
    monkeypatch.setattr(engine_train, "PROJECT_ROOT", str(tmp_path))
    monkeypatch.setattr(shared_utils, "PROJECT_ROOT", str(tmp_path))
    salida: dict = {}

    def run(payload: dict):
        monkeypatch.setattr(engine_train, "read_request_payload", lambda: payload)
        monkeypatch.setattr(engine_train, "write_response", lambda tipo, body: salida.update(tipo=tipo, body=body))
        monkeypatch.setattr(engine_train, "write_error", lambda code, msg: salida.update(error=msg))
        try:
            engine_train.main()
        except SystemExit as exc:
            salida["exit"] = exc.code
        return salida

    return run


def test_entrena_guarda_modelo_firmado_y_describe_su_procedencia(entrenar, tmp_path):
    salida = entrenar(_payload())

    assert salida["tipo"] == "TRAIN_RESPONSE" and salida["body"]["status"] == "success"
    modelo = tmp_path / "models" / "random_forest_1h_BTCUSDT_StressTestStrategy.pkl"
    assert modelo.exists()
    assert modelo.with_suffix(".manifest.json").exists()
    meta = json.loads(modelo.with_suffix(".metadata.json").read_text())
    assert meta["provenance"]["symbol"] == "BTCUSDT"
    assert meta["provenance"]["train_rows"] == salida["body"]["data_info"]["velas_entrenamiento"]
    assert {"trained_at", "git_sha", "data_from_ts", "data_to_ts", "metrics"} <= meta["provenance"].keys()


def test_la_ultima_fila_de_train_se_descarta_para_no_tocar_el_test(entrenar):
    body = entrenar(_payload())["body"]
    info = body["data_info"]

    # 80 % de las filas utilizables menos la última de train (su etiqueta mira la primera de test)
    assert info["velas_entrenamiento"] == int(info["total_velas_usadas"] * 0.8) - 1
    assert info["velas_entrenamiento"] + info["velas_prueba"] <= info["total_velas_usadas"] - 1


def test_con_tres_clases_las_metricas_dicen_que_son_ponderadas(entrenar):
    claves = list(entrenar(_payload())["body"]["metrics"])

    assert any("ponderada" in k for k in claves if k.startswith("Precision"))


@pytest.mark.parametrize(("cambio", "mensaje"), [
    ({"warmup_candles": None}, "warmup_candles es obligatorio"),
    ({"dataset": []}, "dataset esta vacio"),
    ({"strategy_name": None}, "strategy_name es obligatorio"),
    ({"symbol": "../etc"}, "símbolo"),
])
def test_un_payload_incompleto_falla_con_un_error_explicito(entrenar, cambio, mensaje):
    salida = entrenar(_payload(**cambio))

    assert salida["exit"] == 1 and mensaje.lower() in salida["error"].lower()
