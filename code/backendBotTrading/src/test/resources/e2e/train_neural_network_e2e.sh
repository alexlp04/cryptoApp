#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../../../../../.." && pwd)"
BACKEND_DIR="$ROOT_DIR/code/backendBotTrading"
MODEL_DIR="$ROOT_DIR/code/models"
MODEL_FILE="$MODEL_DIR/neural_network_1m_ETHUSDT_StressTestStrategy.keras"
META_FILE="$MODEL_DIR/neural_network_1m_ETHUSDT_StressTestStrategy.metadata.json"
RUN_LOG="$ROOT_DIR/logs/e2e_train_neural_network.out"

mkdir -p "$MODEL_DIR"
mkdir -p "$ROOT_DIR/logs"
rm -f "$MODEL_FILE" "$META_FILE" "$RUN_LOG"

USER_NAME="e2e_nn_$(date +%s)"
USER_PASS="e2e_pass_123"

# La app valida PYTHON_EXECUTABLE como .venv/bin/python3 relativo a backendBotTrading.
# Debe enlazarse el venv completo (no solo bin) para resolver correctamente site-packages.
if [[ ! -x "$BACKEND_DIR/.venv/bin/python3" && -x "$ROOT_DIR/.venv/bin/python3" ]]; then
  rm -rf "$BACKEND_DIR/.venv"
  ln -sfn "$ROOT_DIR/.venv" "$BACKEND_DIR/.venv"
fi

cat > /tmp/e2e_train_commands.txt <<EOF
signup
$USER_NAME
$USER_PASS
login
$USER_NAME
$USER_PASS
train -neural_network -c ETHUSDT -t 1m -s StressTestStrategy -d 180 -p epochs=3,batch_size=128,learning_rate=0.001,dropout_rate=0.3
models
exit
EOF

cd "$BACKEND_DIR"

# Ejecuta la CLI real de extremo a extremo con timeout defensivo.
timeout 3600 mvn -q -DskipTests spring-boot:run < /tmp/e2e_train_commands.txt > "$RUN_LOG" 2>&1 || {
  echo "[E2E] Fallo en ejecución de CLI. Revisar: $RUN_LOG"
  exit 1
}

if [[ ! -f "$MODEL_FILE" ]]; then
  echo "[E2E] No se genero el modelo esperado: $MODEL_FILE"
  echo "[E2E] Ultimas lineas del log:"
  tail -n 80 "$RUN_LOG" || true
  exit 1
fi

if ! grep -qi "RESULTADOS DEL MODELO\|status\|model_saved_at" "$RUN_LOG"; then
  echo "[E2E] El comando train no mostro salida esperada de resultado"
  tail -n 80 "$RUN_LOG" || true
  exit 1
fi

echo "[E2E] OK - Modelo generado: $MODEL_FILE"
ls -lh "$MODEL_FILE"
