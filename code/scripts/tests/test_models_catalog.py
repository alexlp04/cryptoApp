"""El catálogo que imprime `models` (ModelsCommand.java) debe coincidir con lo que engine_train acepta."""
from __future__ import annotations

import re
from pathlib import Path

import pytest
from shared_utils import build_sklearn_model

CATALOG = Path(__file__).resolve().parents[2] / (
    "backendBotTrading/src/main/java/com/bottrading/interfaces/cli/commands/ModelsCommand.java")
NEURAL = {"neural_network"}  # Keras: build_sklearn_model no lo construye


def _catalog() -> dict[str, dict[str, str]]:
    """{modelo: {hiperparámetro: valor de ejemplo}} leído del texto del comando."""
    models: dict[str, dict[str, str]] = {}
    current = None
    for line in CATALOG.read_text(encoding="utf-8").splitlines():
        if m := re.search(r"\(-m (\w+)\)", line):
            current = models.setdefault(m.group(1), {})
        elif current is not None and (m := re.search(r">\s+(\w+)\s+\((\w+)\)", line)):
            name, kind = m.groups()
            default = re.search(r"Def: ([\w.]+)", line)
            sample = default.group(1) if default else {"Int": "5", "Float": "0.5", "Str": "rbf"}[kind]
            current[name] = sample
    return models


def _coerce(value: str):
    for cast in (int, float):
        try:
            return cast(value)
        except ValueError:
            continue
    return value


def test_el_catalogo_se_lee_y_no_esta_vacio():
    catalog = _catalog()
    assert {"random_forest", "xgboost", "lightgbm", "svm"} <= catalog.keys()


@pytest.mark.parametrize(("model", "param", "value"), [
    (m, p, v) for m, params in _catalog().items() if m not in NEURAL for p, v in params.items()
])
def test_cada_hiperparametro_anunciado_llega_al_modelo(model, param, value):
    estimator = build_sklearn_model(model, {param: _coerce(value)}, prefer_gpu=False)

    assert estimator.get_params()[param] == _coerce(value)
