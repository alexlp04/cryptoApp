#!/bin/sh
# La clave de firma de modelos y el token de la API llegan como secreto de Docker (no como variable del compose, que se vería
# en `docker inspect`); el motor Python la lee de CRYPTOAPP_MODEL_KEY.
set -eu
if [ -f /run/secrets/model_key ]; then
    CRYPTOAPP_MODEL_KEY="$(cat /run/secrets/model_key)"
    export CRYPTOAPP_MODEL_KEY
fi
if [ -f /run/secrets/api_token ]; then
    CRYPTOAPP_API_TOKEN="$(cat /run/secrets/api_token)"
    export CRYPTOAPP_API_TOKEN
fi
# shellcheck disable=SC2086 # JAVA_OPTS son varios argumentos
exec java $JAVA_OPTS -jar /app/app.jar
