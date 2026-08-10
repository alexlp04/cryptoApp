#!/usr/bin/env bash
# PostToolUse (Write|Edit): pasa ruff sobre el fichero Python recién editado.
# Si hay hallazgos, los devuelve como additionalContext para que se corrijan en el momento.
set -u

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

if [ -x "$ROOT/.venv/Scripts/python.exe" ]; then
  PY="$ROOT/.venv/Scripts/python.exe"
  RUFF="$ROOT/.venv/Scripts/ruff.exe"
elif [ -x "$ROOT/.venv/bin/python3" ]; then
  PY="$ROOT/.venv/bin/python3"
  RUFF="$ROOT/.venv/bin/ruff"
else
  exit 0
fi

[ -x "$RUFF" ] || exit 0

FILE="$("$PY" -c "
import sys, json
try:
    d = json.load(sys.stdin)
except Exception:
    sys.exit(0)
print(d.get('tool_response', {}).get('filePath') or d.get('tool_input', {}).get('file_path') or '')
" 2>/dev/null)"

case "$FILE" in
  *.py) ;;
  *) exit 0 ;;
esac

[ -f "$FILE" ] || exit 0

# Solo errores reales (pyflakes + sintaxis): nombres indefinidos, imports sin usar,
# redefiniciones. Las reglas de estilo se dejan para `ruff check code/` a mano, para
# que el hook no genere ruido en cada edición.
FINDINGS="$("$RUFF" check --select E9,F --output-format concise "$FILE" 2>&1)" && exit 0

REL="${FILE#"$ROOT/"}"
FINDINGS="$FINDINGS" REL="$REL" "$PY" -c "
import json, os
print(json.dumps({
    'hookSpecificOutput': {
        'hookEventName': 'PostToolUse',
        'additionalContext': 'ruff encontró problemas en ' + os.environ['REL'] + ':\n' + os.environ['FINDINGS'],
    }
}))
" 2>/dev/null || true
exit 0
