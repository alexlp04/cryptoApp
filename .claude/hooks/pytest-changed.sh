#!/usr/bin/env bash
# Stop: ejecuta la suite Python solo si hay ficheros .py modificados en el working tree.
# Informa por systemMessage si falla; nunca bloquea el turno.
set -u

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

if [ -x "$ROOT/.venv/Scripts/python.exe" ]; then
  PY="$ROOT/.venv/Scripts/python.exe"
elif [ -x "$ROOT/.venv/bin/python3" ]; then
  PY="$ROOT/.venv/bin/python3"
else
  exit 0
fi

CHANGED="$(git -C "$ROOT" status --porcelain -- '*.py' 2>/dev/null)"
[ -n "$CHANGED" ] || exit 0

# --color=no: la salida viaja dentro de un JSON, y los codigos ANSI lo vuelven ilegible.
OUTPUT="$(cd "$ROOT" && "$PY" -m pytest code/scripts/tests -q --color=no 2>&1)" && exit 0

OUTPUT="$OUTPUT" "$PY" -c "
import json, os
out = os.environ['OUTPUT'].strip().splitlines()
tail = '\n'.join(out[-15:])
print(json.dumps({'systemMessage': 'pytest FALLA tras los cambios en Python:\n' + tail}))
" 2>/dev/null || true
exit 0
