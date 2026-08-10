"""Persistencia de estudios Optuna en engine_optimize."""
from __future__ import annotations

import os
from datetime import UTC, datetime

import optuna
import pytest
from engine_optimize import (
    DEFAULT_OPTUNA_DB_RELPATH,
    OPTUNA_STORAGE_ENV_VAR,
    build_study_name,
    resolve_optuna_storage,
)


@pytest.fixture(autouse=True)
def _clear_storage_env(monkeypatch):
    monkeypatch.delenv(OPTUNA_STORAGE_ENV_VAR, raising=False)


class TestResolveOptunaStorage:

    def test_usa_sqlite_bajo_results_por_defecto(self, tmp_path):
        url = resolve_optuna_storage(str(tmp_path))

        assert url is not None
        assert url.startswith("sqlite:///")
        # El directorio debe quedar creado, no solo la URL construida.
        assert (tmp_path / "results" / "optuna").is_dir()

    def test_la_variable_de_entorno_tiene_prioridad(self, tmp_path, monkeypatch):
        monkeypatch.setenv(OPTUNA_STORAGE_ENV_VAR, "mysql://user:pw@localhost/optuna")

        assert resolve_optuna_storage(str(tmp_path)) == "mysql://user:pw@localhost/optuna"
        # Con override no debe tocar el disco.
        assert not (tmp_path / "results").exists()

    def test_entorno_en_blanco_se_ignora(self, tmp_path, monkeypatch):
        monkeypatch.setenv(OPTUNA_STORAGE_ENV_VAR, "   ")

        assert resolve_optuna_storage(str(tmp_path)).startswith("sqlite:///")

    def test_degrada_a_memoria_si_no_puede_crear_el_directorio(self, tmp_path, monkeypatch):
        def _boom(*_args, **_kwargs):
            raise OSError("disco de solo lectura")

        monkeypatch.setattr(os, "makedirs", _boom)

        # None significa "estudio en memoria": preferible a abortar la optimización.
        assert resolve_optuna_storage(str(tmp_path)) is None

    def test_la_url_usa_barras_normales(self, tmp_path):
        # SQLAlchemy no acepta barras invertidas de Windows en la URL.
        assert "\\" not in resolve_optuna_storage(str(tmp_path))


class TestBuildStudyName:

    def test_incluye_modelo_simbolo_y_timeframe(self):
        name = build_study_name("lightgbm", "BTCUSDT", "1h",
                                datetime(2026, 8, 10, 14, 30, 5, tzinfo=UTC))

        assert name == "optimize_lightgbm_BTCUSDT_1h_20260810-143005"

    def test_dos_ejecuciones_no_comparten_nombre(self):
        primera = build_study_name("xgboost", "ETHUSDT", "4h",
                                   datetime(2026, 8, 10, 10, 0, 0, tzinfo=UTC))
        segunda = build_study_name("xgboost", "ETHUSDT", "4h",
                                   datetime(2026, 8, 10, 10, 0, 1, tzinfo=UTC))

        assert primera != segunda


class TestPersistenciaReal:

    def test_el_estudio_sobrevive_al_proceso(self, tmp_path):
        """Un estudio guardado debe poder recargarse desde la misma URL."""
        url = resolve_optuna_storage(str(tmp_path))
        name = build_study_name("lightgbm", "BTCUSDT", "1h")

        study = optuna.create_study(direction="maximize", study_name=name, storage=url)
        study.optimize(lambda trial: trial.suggest_float("x", 0.0, 1.0), n_trials=3)
        del study

        recargado = optuna.load_study(study_name=name, storage=url)

        assert len(recargado.trials) == 3
        assert (tmp_path / DEFAULT_OPTUNA_DB_RELPATH).is_file()
