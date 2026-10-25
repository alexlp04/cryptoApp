"""test_shared_utils_runtime.py — Logging de los engines y carga dinámica de estrategias."""
from __future__ import annotations

import logging
import sys

import pytest
import shared_utils


@pytest.fixture
def clean_root_logger():
    root = logging.getLogger()
    saved, level = list(root.handlers), root.level
    yield root
    for handler in list(root.handlers):
        if handler not in saved:
            root.removeHandler(handler)
            handler.close()
    root.setLevel(level)


# ── Logging (#76) ───────────────────────────────────────────────────────────

def _tagged(root):
    return [h for h in root.handlers if getattr(h, shared_utils._HANDLER_TAG, False)]


def test_llamadas_repetidas_no_acumulan_handlers(clean_root_logger, tmp_path, monkeypatch):
    monkeypatch.setattr(shared_utils, "PROJECT_ROOT", str(tmp_path))

    shared_utils.setup_engine_logging("a")
    shared_utils.setup_engine_logging("b")

    assert len(_tagged(clean_root_logger)) == 2  # fichero + stream, no 4
    assert (tmp_path / "logs" / "b.log").exists()


def test_el_nivel_root_es_info_aunque_otra_libreria_lo_dejara_en_warning(clean_root_logger, tmp_path, monkeypatch):
    monkeypatch.setattr(shared_utils, "PROJECT_ROOT", str(tmp_path))
    clean_root_logger.setLevel(logging.WARNING)
    clean_root_logger.addHandler(logging.NullHandler())

    shared_utils.setup_engine_logging("a")

    assert clean_root_logger.level == logging.INFO


def test_el_fichero_de_log_rota(clean_root_logger, tmp_path, monkeypatch):
    monkeypatch.setattr(shared_utils, "PROJECT_ROOT", str(tmp_path))

    shared_utils.setup_engine_logging("a")

    file_handlers = [h for h in _tagged(clean_root_logger) if isinstance(h, logging.handlers.RotatingFileHandler)]
    assert len(file_handlers) == 1 and file_handlers[0].maxBytes > 0 and file_handlers[0].backupCount > 0


def test_logs_no_escribible_degrada_a_stream_en_vez_de_morir(clean_root_logger, tmp_path, monkeypatch):
    bloqueo = tmp_path / "no-es-un-directorio"
    bloqueo.write_text("x")
    monkeypatch.setattr(shared_utils, "PROJECT_ROOT", str(bloqueo))  # logs/ no se puede crear dentro de un fichero

    logger = shared_utils.setup_engine_logging("a")

    assert logger is not None
    assert [type(h) for h in _tagged(clean_root_logger)] == [logging.StreamHandler]


# ── Carga de estrategias (#77) ──────────────────────────────────────────────

BASE_IMPORT = "from strategies.BaseStrategy import BaseStrategy\n"


def _write(tmp_path, name, body):
    path = tmp_path / f"{name}.py"
    path.write_text(body)
    return str(path)


def test_elige_la_clase_del_fichero_y_no_la_importada(tmp_path):
    path = _write(tmp_path, "Mine", (
        "from strategies.StressTestStrategy import StressTestStrategy\n"
        "class Mine(StressTestStrategy):\n"
        "    pass\n"))

    strategy = shared_utils.load_strategy_by_path(path)

    assert type(strategy).__name__ == "Mine"


def test_ignora_las_clases_abstractas(tmp_path):
    path = _write(tmp_path, "Abstracta", (
        "from strategies.StressTestStrategy import StressTestStrategy\n"
        "import abc\n"
        "class Plantilla(StressTestStrategy):\n"
        "    @abc.abstractmethod\n"
        "    def extra(self): ...\n"
        "class Concreta(StressTestStrategy):\n"
        "    pass\n"))

    assert type(shared_utils.load_strategy_by_path(path)).__name__ == "Concreta"


def test_varias_estrategias_concretas_en_un_fichero_es_un_error_explicito(tmp_path):
    path = _write(tmp_path, "Dos", (
        "from strategies.StressTestStrategy import StressTestStrategy\n"
        "class Uno(StressTestStrategy): ...\n"
        "class Dos(StressTestStrategy): ...\n"))

    with pytest.raises(ValueError, match="Uno, Dos"):
        shared_utils.load_strategy_by_path(path)


def test_el_modulo_queda_registrado_con_nombre_derivado_del_fichero(tmp_path):
    path = _write(tmp_path, "Registrada", (
        "from strategies.StressTestStrategy import StressTestStrategy\n"
        "class Registrada(StressTestStrategy): ...\n"))

    shared_utils.load_strategy_by_path(path)

    assert "user_strategy_Registrada" in sys.modules


def test_un_fichero_que_falla_al_importar_no_deja_el_modulo_a_medias(tmp_path):
    path = _write(tmp_path, "Rota", "raise RuntimeError('boom')\n")

    with pytest.raises(RuntimeError):
        shared_utils.load_strategy_by_path(path)

    assert "user_strategy_Rota" not in sys.modules
