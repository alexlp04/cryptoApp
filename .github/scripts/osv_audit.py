"""osv_audit.py — audita las dependencias Java contra OSV (api.osv.dev), sin la base del NVD.

Entrada (stdin): la salida de `mvn dependency:list -DoutputFile=...` (una dependencia por línea,
`grupo:artefacto:tipo:versión:ámbito`). Sale con código 1 si alguna tiene un aviso que no esté en
`.github/osv-ignore.txt` (un ID por línea, `#` para comentarios: los riesgos aceptados y por qué).

Uso:  python osv_audit.py < deps.txt
"""
from __future__ import annotations

import json
import re
import sys
import urllib.request
from collections.abc import Callable
from pathlib import Path

OSV = "https://api.osv.dev/v1"
IGNORE_FILE = Path(__file__).resolve().parent.parent / "osv-ignore.txt"  # fijo: ninguna ruta llega por argumento
BATCH = 500  # OSV admite hasta 1000 consultas por petición
_DEP = re.compile(r"^\s*([\w.\-]+):([\w.\-]+):[\w.\-]+(?::[\w.\-]+)?:([\w.\-]+):(?:compile|runtime|provided|test|system)\b")


def parse_dependencies(text: str) -> list[tuple[str, str]]:
    """[(grupo:artefacto, versión)] sin repetidos; ignora lo que no sea una línea de dependencia."""
    found = {(f"{m[1]}:{m[2]}", m[3]) for line in text.splitlines() if (m := _DEP.match(line))}
    return sorted(found)


def _http(url: str, body: dict | None = None) -> dict:
    request = urllib.request.Request(
        url, data=json.dumps(body).encode() if body is not None else None,
        headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


def find_vulnerabilities(deps: list[tuple[str, str]], http: Callable[..., dict] = _http) -> dict[tuple[str, str], list[str]]:
    """{(dependencia, versión): [ids de aviso]} solo para las que tienen alguno.

    shortcut: no se sigue `next_page_token`; una dependencia con más de unos cientos de avisos no existe aquí.
    """
    found: dict[tuple[str, str], list[str]] = {}
    for i in range(0, len(deps), BATCH):
        chunk = deps[i: i + BATCH]
        queries = [{"package": {"name": name, "ecosystem": "Maven"}, "version": version} for name, version in chunk]
        for dep, result in zip(chunk, http(f"{OSV}/querybatch", {"queries": queries})["results"], strict=True):
            if ids := [v["id"] for v in result.get("vulns", [])]:
                found[dep] = ids
    return found


def load_ignored(text: str) -> set[str]:
    return {line.split("#", 1)[0].strip() for line in text.splitlines() if line.split("#", 1)[0].strip()}


def main() -> int:
    ignored = load_ignored(IGNORE_FILE.read_text(encoding="utf-8")) if IGNORE_FILE.exists() else set()
    deps = parse_dependencies(sys.stdin.read())
    if not deps:
        print("No se recibió ninguna dependencia por la entrada estándar: ¿falló `mvn dependency:list`?")
        return 1
    print(f"{len(deps)} dependencias Java consultadas en OSV")
    pending = {dep: [i for i in ids if i not in ignored] for dep, ids in find_vulnerabilities(deps, _http).items()}
    pending = {dep: ids for dep, ids in pending.items() if ids}
    for (name, version), ids in sorted(pending.items()):
        for vuln_id in ids:
            summary = _http(f"{OSV}/vulns/{vuln_id}").get("summary", "")
            print(f"  {name} {version}: {vuln_id} {summary}  https://osv.dev/{vuln_id}")
    print(f"{sum(len(v) for v in pending.values())} avisos sin ignorar en {len(pending)} dependencias"
          if pending else "Sin avisos (descontados los ignorados)")
    return 1 if pending else 0


if __name__ == "__main__":
    sys.exit(main())
