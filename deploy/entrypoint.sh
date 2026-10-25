#!/bin/sh
# La clave de firma de modelos llega como secreto de Docker (no como variable del compose, que se vería
# en `docker inspect`); el motor Python la lee de CRYPTOAPP_MODEL_KEY.
set -eu
if [ -f /run/secrets/model_key ]; then
    CRYPTOAPP_MODEL_KEY="$(cat /run/secrets/model_key)"
    export CRYPTOAPP_MODEL_KEY
fi
# shellcheck disable=SC2086 # JAVA_OPTS son varios argumentos
exec java $JAVA_OPTS -jar /app/app.jar
