"""test_osv_audit.py — El auditor de dependencias Java (OSV) lee `mvn dependency:list` y respeta los ignorados."""
from __future__ import annotations

import importlib.util
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
_spec = importlib.util.spec_from_file_location("osv_audit", ROOT / ".github/scripts/osv_audit.py")
assert _spec is not None
assert _spec.loader is not None
osv_audit = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(osv_audit)

MVN_OUTPUT = """
The following files have been resolved:
   org.springframework:spring-webmvc:jar:6.2.19:compile -- module spring.webmvc [auto]
   ch.qos.logback:logback-classic:jar:1.5.34:runtime
   org.lz4:lz4-java:jar:classes:1.8.0:compile
   org.springframework:spring-webmvc:jar:6.2.19:compile -- module spring.webmvc [auto]

"""


def test_lee_las_dependencias_sin_repetir_y_con_clasificador():
    assert osv_audit.parse_dependencies(MVN_OUTPUT) == [
        ("ch.qos.logback:logback-classic", "1.5.34"),
        ("org.lz4:lz4-java", "1.8.0"),
        ("org.springframework:spring-webmvc", "6.2.19"),
    ]


def test_consulta_osv_en_lotes_y_devuelve_solo_las_vulnerables(monkeypatch):
    monkeypatch.setattr(osv_audit, "BATCH", 2)
    llamadas = []

    def http(url, body=None):
        llamadas.append(len(body["queries"]))
        return {"results": [{"vulns": [{"id": "GHSA-x"}]} if q["package"]["name"] == "a:b" else {}
                            for q in body["queries"]]}

    found = osv_audit.find_vulnerabilities([("a:b", "1"), ("c:d", "2"), ("e:f", "3")], http)

    assert found == {("a:b", "1"): ["GHSA-x"]}
    assert llamadas == [2, 1]
    assert osv_audit.parse_dependencies(MVN_OUTPUT)[0][0] == "ch.qos.logback:logback-classic"


def test_los_ignorados_se_leen_sin_comentarios():
    assert osv_audit.load_ignored("# motivo\nGHSA-1  # aceptado\n\nGHSA-2\n") == {"GHSA-1", "GHSA-2"}


def test_el_fichero_de_ignorados_del_repo_solo_lleva_ids_de_osv():
    ids = osv_audit.load_ignored((ROOT / ".github/osv-ignore.txt").read_text(encoding="utf-8"))
    assert ids
    assert {i for i in ids if not i.startswith(("GHSA-", "CVE-"))} == set()


def test_main_lee_stdin_descuenta_los_ignorados_y_sale_con_el_codigo_correcto(monkeypatch, capsys):
    import io

    monkeypatch.setattr(osv_audit.sys, "stdin", io.StringIO(MVN_OUTPUT))
    monkeypatch.setattr(osv_audit, "_http", lambda url, body=None: (
        {"results": [{"vulns": [{"id": "GHSA-nuevo"}]} for _ in body["queries"]]} if body else {"summary": "aviso"}))
    monkeypatch.setattr(osv_audit, "IGNORE_FILE", ROOT / ".github/osv-ignore.txt")

    assert osv_audit.main() == 1
    assert "GHSA-nuevo" in capsys.readouterr().out

    monkeypatch.setattr(osv_audit.sys, "stdin", io.StringIO(""))
    assert osv_audit.main() == 1  # sin dependencias: no se da por bueno un mvn roto
