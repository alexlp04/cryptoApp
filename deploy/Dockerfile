# syntax=docker/dockerfile:1
# CryptoApp para Raspberry Pi 5 (arm64) y x86. Una sola imagen: Java orquesta y lanza los motores Python.
# Contexto de construcción: la raíz del repositorio (ver deploy/docker-compose.yml).

# --- 1. Compilar el jar ------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src/code/backendBotTrading
COPY code/backendBotTrading/pom.xml ./
RUN --mount=type=cache,target=/root/.m2 mvn -q -B dependency:go-offline
COPY code/backendBotTrading/src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -q -B -DskipTests package \
    && cp target/backendBotTrading-*.jar /app.jar

# --- 2. Runtime: Python 3.11 (el de los modelos) + JRE 21 --------------------------------------
FROM python:3.11-slim-bookworm
# El JRE de Temurin (glibc 2.35) corre en Debian bookworm (2.36). libgomp: lo exigen xgboost y lightgbm.
COPY --from=eclipse-temurin:21-jre-jammy /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk \
    PATH=/opt/java/openjdk/bin:/opt/venv/bin:$PATH \
    CRYPTOAPP_PYTHON=/opt/venv/bin/python \
    PYTHONUNBUFFERED=1
RUN apt-get update \
    && apt-get install -y --no-install-recommends libgomp1 tini \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --create-home --uid 1000 app

# Dependencias Python: las de deploy/requirements.lock (generado desde requirements.txt con deploy/lock-requirements.sh),
# con hashes de todas las ruedas: la instalación es reproducible y verifica lo que descarga. Solo ruedas, para no
# ejecutar scripts de instalación; la excepción es `ta`, que solo se publica como código fuente.
# TensorFlow no va en la imagen: no hay rueda de la versión fijada (2.21.0) para arm64, así que los modelos Keras no
# se cargan en ella por ahora; cargan los de scikit-learn, XGBoost y LightGBM.
COPY deploy/requirements.lock /tmp/requirements.lock
RUN python -m venv /opt/venv \
    && pip install --no-cache-dir --require-hashes --only-binary :all: --no-binary ta -r /tmp/requirements.lock \
    && rm -f /tmp/requirements.lock

# Disposición que PathConfig espera: code/backendBotTrading existe (marca la raíz del proyecto), con scripts y
# estrategias al lado; models, results y logs son volúmenes.
WORKDIR /app
COPY --from=build /app.jar /app/app.jar
COPY code/scripts /app/code/scripts
COPY code/strategies /app/code/strategies
COPY deploy/entrypoint.sh /app/entrypoint.sh
RUN mkdir -p /app/code/backendBotTrading /app/models /app/results /app/logs \
    && chmod +x /app/entrypoint.sh \
    && chown -R app:app /app
USER app

# Heap acotado: en una Pi comparten RAM la JVM, MySQL y los motores Python.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=35 -XX:+UseSerialGC"
ENTRYPOINT ["tini", "--", "/app/entrypoint.sh"]
