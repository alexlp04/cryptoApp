#!/bin/sh
# Regenera deploy/requirements.lock a partir de requirements.txt: las mismas dependencias que el Dockerfile instala
# (sin herramientas de desarrollo ni TensorFlow), con los hashes de TODAS las ruedas, de modo que sirve igual para
# x86 y arm64. Hay que repetirlo cada vez que cambie una versión en requirements.txt (un test lo comprueba).
# Necesita uv: pip install uv
set -eu
cd "$(dirname "$0")/.."
grep -vE '^\s*(#|$)' requirements.txt | sed 's/ *#.*//' \
  | grep -vE '^(pytest|pytest-cov|hypothesis|ruff|mypy|optuna-dashboard|tensorflow)==' > /tmp/runtime.in
uv pip compile /tmp/runtime.in --universal --generate-hashes --python-version 3.11 --no-header --no-annotate \
  --output-file deploy/requirements.lock
