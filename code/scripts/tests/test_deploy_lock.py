"""test_deploy_lock.py — El bloqueo de la imagen Docker sigue a requirements.txt y trae hashes."""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
DEV_ONLY = {"pytest", "pytest-cov", "hypothesis", "ruff", "mypy", "optuna-dashboard", "tensorflow"}  # no van en la imagen


def _norm(name: str) -> str:
    return re.sub(r"[-_.]+", "-", name).lower()


def _pins(path: Path) -> dict[str, str]:
    found = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        m = re.match(r"^([A-Za-z0-9_.-]+)==([^\s\\#;]+)", line)
        if m:
            found[_norm(m.group(1))] = m.group(2)
    return found


def test_el_bloqueo_tiene_las_versiones_de_requirements_txt():
    runtime = {k: v for k, v in _pins(ROOT / "requirements.txt").items() if k not in DEV_ONLY}
    lock = _pins(ROOT / "deploy" / "requirements.lock")

    desfasadas = {k: (v, lock.get(k)) for k, v in runtime.items() if lock.get(k) != v}

    assert not desfasadas, f"regenera deploy/requirements.lock con deploy/lock-requirements.sh: {desfasadas}"


def test_cada_dependencia_del_bloqueo_lleva_hashes():
    texto = (ROOT / "deploy" / "requirements.lock").read_text(encoding="utf-8")
    bloques = re.split(r"\n(?=[A-Za-z0-9_.-]+==)", texto.strip())

    assert all("--hash=sha256:" in b for b in bloques)
