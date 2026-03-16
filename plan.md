# 1) Resumen Ejecutivo

La arquitectura actual funciona como motor híbrido Java-Python, pero presenta **riesgos estructurales de ejecución y consistencia** que ya impactan operación real (por ejemplo, fallo del cálculo de indicadores por desalineación de protocolo).  
La auditoría cubrió la totalidad solicitada: **58 archivos Java**, **13 archivos Python**, configuración Maven/Spring/Logback y **SQL base**.

Diagnóstico general:

- Fortalezas:
  - Separación inicial por paquetes (`services`, `repositories`, `utils`).
  - Uso de locks pesimistas en operaciones contables sensibles.
  - Introducción de timeouts/retries en varios servicios Java-Python.
- Riesgos principales:
  - **Desalineación de contratos Java-Python (JSON vs MessagePack) en indicadores**.
  - **Inconsistencia contable en `riesgo_abierto`** (unidades mezcladas entre porcentaje y monto).
  - **Schema drift SQL/JPA** (definiciones duplicadas/erróneas y constraints inconsistentes).
  - Acoplamiento fuerte CLI-negocio y estado de dominio basado en strings dispersos.
  - Inconsistencias de naming/model registry entre entrenamiento, predicción y validación de modelos.

---

# 2) Cobertura de Análisis (inventario por paquete/categoría con conteos)

## Java (`code/backendBotTrading/src/main/java`) - 58 archivos

- `com.bottrading` (raíz): **2**
- `com.bottrading.beans`: **14**
- `com.bottrading.config`: **2**
- `com.bottrading.exceptions`: **8**
- `com.bottrading.repositories`: **7**
- `com.bottrading.services`: **14**
- `com.bottrading.utils`: **11**

## Python - 13 archivos

- `code/scripts`: **7**
- `code/strategies`: **6**

## Configuración y SQL

- `code/backendBotTrading/pom.xml`
- `code/backendBotTrading/src/main/resources/META-INF/application.properties`
- `code/backendBotTrading/src/main/resources/logback-spring.xml`
- `info/tablas.sql`

---

# 3) Hallazgos por Severidad

## CRÍTICO

1. **Ruptura de protocolo Java-Python en cálculo de indicadores (causa fallo runtime)**
   - Java envía MessagePack por stream: `code/backendBotTrading/src/main/java/com/bottrading/services/IndicatorsService.java:137`
   - Python espera JSON de `stdin` y hace `json.loads`: `code/scripts/engine_indicators.py:117`, `code/scripts/engine_indicators.py:127`
   - Resultado: errores de parseo y salida vacía/error code 1 en flujo de indicadores.

2. **Bug de serialización por cierre prematuro de stream en chunks**
   - `serializeVelasToStream` cierra el `OutputStream` recibido cuando `compress=false`: `code/backendBotTrading/src/main/java/com/bottrading/utils/DataSerializationUtils.java:33`
   - `streamVelasInChunks` reutiliza ese stream para múltiples chunks: `code/backendBotTrading/src/main/java/com/bottrading/utils/DataSerializationUtils.java:78`, `code/backendBotTrading/src/main/java/com/bottrading/utils/DataSerializationUtils.java:84`
   - Riesgo: truncamiento/pipe roto en cargas grandes, comportamiento no determinista.

3. **Inconsistencia contable en riesgo abierto (corrupción de estado financiero)**
   - Se compromete capital en monto, pero `risk` se pasa como porcentaje: `code/backendBotTrading/src/main/java/com/bottrading/services/PaperTradingService.java:80`, `code/backendBotTrading/src/main/java/com/bottrading/services/PaperTradingService.java:90`
   - `AccountingService` suma/resta ese `risk` en `riesgo_abierto` como si fuera monto: `code/backendBotTrading/src/main/java/com/bottrading/services/AccountingService.java:157`, `code/backendBotTrading/src/main/java/com/bottrading/services/AccountingService.java:190`
   - Además al cerrar se envía `BigDecimal.ZERO`: `code/backendBotTrading/src/main/java/com/bottrading/services/PaperTradingService.java:122`
   - Efecto: `riesgo_abierto` no representa exposición real y puede crecer incorrectamente.

## ALTO

1. **Schema drift SQL: DDL inconsistente y no idempotente**
   - FK a tabla inexistente `instancias_estrategia`: `info/tablas.sql:70`
   - Duplicado de `CREATE TABLE instancia_simbolos`: `info/tablas.sql:67`, `info/tablas.sql:73`
   - `USE bottradingdb` dentro del script rompe portabilidad: `info/tablas.sql:72`
   - Constraint JPA en `Vela` usa columna `interval` mientras entidad mapea `time_interval`: `code/backendBotTrading/src/main/java/com/bottrading/beans/Vela.java:12`, `info/tablas.sql:18`, `info/tablas.sql:32`

2. **Estrategias Python no cumplen contrato abstracto de `BaseStrategy`**
   - Contrato exige `get_stop_loss` y `get_take_profit`: `code/strategies/BaseStrategy.py:27`, `code/strategies/BaseStrategy.py:31`
   - Varias estrategias no implementan esos métodos (`AITraderStrategy`, `ScalpingRSIStrategy`, `StressTestStrategy`, `ToggleStrategy`): `code/strategies/AITraderStrategy.py:4`, `code/strategies/ScalpingRSIStrategy.py:5`, `code/strategies/StressTestStrategy.py:3`, `code/strategies/ToggleStrategy.py:3`
   - Riesgo: `TypeError` al instanciar estrategias en runtime.

3. **Desalineación de naming y descubrimiento de modelos IA**
   - Entrenamiento guarda `model_type_timeframe_symbol...`: `code/scripts/engine_train.py:332`
   - Predicción busca `model_type_symbol_timeframe`: `code/scripts/engine_predict.py:48`
   - Validación Java en CLI busca solo `<modelo>.pkl`: `code/backendBotTrading/src/main/java/com/bottrading/utils/PathConfig.java:110`, `code/backendBotTrading/src/main/java/com/bottrading/utils/PathConfig.java:115`, usada en `code/backendBotTrading/src/main/java/com/bottrading/AppBot.java:307`
   - Resultado: falsos “modelo no encontrado” y rutas incoherentes.

4. **Ruta de resultados de backtest inconsistente entre Java y Python**
   - Python guarda en `project_root/<estrategia>`: `code/scripts/engine_backtest.py:40`
   - Java persiste/lee en `code/results/<estrategia>`: `code/backendBotTrading/src/main/java/com/bottrading/utils/PathConfig.java:25`, `code/backendBotTrading/src/main/java/com/bottrading/services/FileService.java:301`
   - Riesgo: artefactos repartidos, pérdida de trazabilidad y confusión operativa.

5. **Proceso incremental de fetch no atómico (delete + fetch)**
   - Borra indicadores/velas y luego descarga fuera de transacción de negocio: `code/backendBotTrading/src/main/java/com/bottrading/services/FetchService.java:101`, `code/backendBotTrading/src/main/java/com/bottrading/services/FetchService.java:102`, `code/backendBotTrading/src/main/java/com/bottrading/services/FetchService.java:106`
   - Si Python falla tras borrar, deja huecos de datos.

## MEDIO

1. **God Class / acoplamiento CLI-negocio**
   - `AppBot` concentra parsing, UX, validaciones y orquestación transaccional: `code/backendBotTrading/src/main/java/com/bottrading/AppBot.java:19`, `code/backendBotTrading/src/main/java/com/bottrading/AppBot.java:77`, `code/backendBotTrading/src/main/java/com/bottrading/AppBot.java:273`, `code/backendBotTrading/src/main/java/com/bottrading/AppBot.java:570`

2. **Exposición de hash de contraseña en `toString`**
   - `code/backendBotTrading/src/main/java/com/bottrading/beans/Usuario.java:37`

3. **Estados de dominio con strings hardcodeados**
   - Ejemplo en `PaperTradingService` y listados por string literal: `code/backendBotTrading/src/main/java/com/bottrading/services/PaperTradingService.java:59`, `code/backendBotTrading/src/main/java/com/bottrading/services/EstrategiaService.java:208`
   - Riesgo de typos e inconsistencias de transición de estado.

4. **Deriva de configuración en runtime vs properties**
   - `application.properties` activa SQL log: `code/backendBotTrading/src/main/resources/META-INF/application.properties:14`
   - `BotApplication` lo apaga por `System.setProperty`: `code/backendBotTrading/src/main/java/com/bottrading/BotApplication.java:69`
   - Similar con `ddl-auto`: `code/backendBotTrading/src/main/resources/META-INF/application.properties:6`, `code/backendBotTrading/src/main/java/com/bottrading/BotApplication.java:68`

5. **Dependencias duplicadas de dotenv en Maven**
   - `java-dotenv`: `code/backendBotTrading/pom.xml:66`
   - `dotenv-java`: `code/backendBotTrading/pom.xml:87`
   - Riesgo de redundancia/conflicto y mayor superficie de mantenimiento.

## BAJO

1. **Ruido potencial del protocolo stdout por `print` en estrategia**
   - `code/strategies/ToggleStrategy.py:13`, `code/strategies/ToggleStrategy.py:18`
   - Puede contaminar streams donde Java espera JSON/señales.

2. **Uso extensivo de `catch (Exception)` en servicios core**
   - Ejemplo `TradingService`: `code/backendBotTrading/src/main/java/com/bottrading/services/TradingService.java:142`, `code/backendBotTrading/src/main/java/com/bottrading/services/TradingService.java:262`
   - Reduce observabilidad semántica de fallos.

3. **Join sin timeout explícito en sincronización de market data paralela**
   - `code/backendBotTrading/src/main/java/com/bottrading/services/MarketDataService.java:65`

---

# 4) Hallazgos por Capa/Paquete

## Capa de Entrada (CLI)

- `AppBot` mezcla UI/validación/orquestación de casos de uso.
- Validación de modelos en CLI no refleja naming real de artefactos (`PathConfig.existeModelo`).

## Capa de Aplicación/Servicios

- `TradingService` y `EstrategiaService` concentran mucha coordinación y estados mutables.
- `FetchService` y `IndicatorsService` tienen avances de resiliencia, pero contratos de protocolo no unificados.
- `AccountingService` implementa locking/transacciones, pero conserva bug de unidad en riesgo.

## Capa de Dominio (beans)

- Dominio mayormente anémico (entidades como contenedores de datos).
- Estados de negocio en strings, sin máquina de estados ni invariantes centralizadas.

## Capa de Persistencia (repositories + SQL)

- Uso de JPA/JdbcTemplate mixto.
- SQL manual presenta drift frente a mapeos JPA.
- Falta estrategia formal de migraciones versionadas.

## Bridge Java-Python

- Coexisten protocolos (JSON, MessagePack) sin contrato formal versionado.
- Algunos componentes usan rutas/convenciones incompatibles entre sí (modelos, resultados backtest).

## Observabilidad y Configuración

- Configuración efectiva repartida entre `application.properties` y `System.setProperty`.
- Logging funcional, pero faltan métricas operativas de SLA del bridge (latencia, retries, timeouts, parse failures).

---

# 5) Top 15 Refactors accionables (clase actual -> cambio recomendado)

1. `IndicatorsService` -> Unificar protocolo con `engine_indicators.py` (MessagePack real en stdin/out o JSON en ambos extremos).
2. `DataSerializationUtils` -> No cerrar stream externo en métodos de serialización chunked; manejar cierre en caller.
3. `PaperTradingService` + `AccountingService` -> Cambiar `risk` a monto monetario explícito (`riskAmount`) y simetría commit/close.
4. `info/tablas.sql` -> Corregir FK `instancia_estrategia`, eliminar duplicados y separar `USE` de DDL portable.
5. `Vela` entidad -> Corregir `@UniqueConstraint` para `time_interval` y alinear completamente con SQL.
6. `PathConfig.existeModelo` -> Resolver por patrón `model_timeframe_symbol[_strategy].{pkl|keras|h5}`.
7. `engine_predict.py` -> Corregir convención de nombre de modelo para coincidir con `engine_train.py`.
8. `engine_backtest.py` -> Escribir resultados bajo `code/results/<estrategia>` (mismo root que Java).
9. `BaseStrategy` + estrategias concretas -> Implementar `get_stop_loss/get_take_profit` en todas o relajar contrato abstracto.
10. `AppBot` -> Extraer command handlers por caso de uso (`TradeCommand`, `TrainCommand`, etc.).
11. `EstrategiaService` -> Introducir `StrategyLifecycleManager` con máquina de estados tipada.
12. `Estado` de estrategia -> Reemplazar strings por enum de dominio + validación de transiciones.
13. `FetchService.fetchIncremental` -> Hacer operación atómica por etapa con snapshot/rollback lógico.
14. `BotApplication` + properties -> Consolidar fuente de verdad de configuración (sin override disperso por código).
15. `Usuario.toString` -> Eliminar `passwordHash` del output y aplicar política de redacción de secretos.

---

# 6) Arquitectura Objetivo recomendada (patrones y límites de capa)

## Estilo propuesto

- **Hexagonal / Ports & Adapters** con módulos:
  - `domain`: entidades, value objects, invariantes, state machine de estrategia.
  - `application`: casos de uso (start/stop/term, fetch, train, backtest).
  - `infrastructure-jpa`: repositorios.
  - `infrastructure-python`: bridge/protocol adapter.
  - `interfaces-cli`: comandos y parsing.

## Límite Java-Python (contract-first)

- Definir **contrato versionado** (`protocol_version`, `message_type`, `schema`) para:
  - `fetch`, `indicators`, `train`, `predict`, `rt`.
- Elegir un solo wire format por canal (recomendado MessagePack + framing por mensaje).
- Añadir “capability handshake” al arrancar procesos Python.

## Transacciones y concurrencia

- `AccountingService` como único “aggregate service” de dinero.
- Operaciones de fetch incremental con estrategia de “staging + swap”.
- Timeouts y retries parametrizados por caso de uso; no hardcoded global único.

## Persistencia y schema governance

- Migraciones versionadas (Flyway/Liquibase).
- Eliminar dependencia de `ddl-auto=update` en producción.
- Contratos JPA y SQL probados con tests de integración y drift checker.

---

# 7) Plan 0-30 / 31-60 / 61-90 días

## 0-30 días (estabilización operativa)

- Corregir protocolo `IndicatorsService` <-> `engine_indicators.py`.
- Fix `DataSerializationUtils` (cierre de stream y chunking).
- Corregir bug contable de `riesgo_abierto`.
- Alinear naming de modelos (`train/predict/PathConfig`).
- Corregir `tablas.sql` crítico y definir baseline de migración.
- Hardening rápido de logs/errores del bridge (códigos y causas tipadas).

## 31-60 días (modularización y contratos)

- Extraer handlers de `AppBot`.
- Introducir enum de estados y validador de transición.
- Crear módulo de protocolo (DTOs/versionado/shared contract tests).
- Unificar path de resultados backtest.
- Revisar dependencias Maven y consolidar dotenv/config runtime.

## 61-90 días (arquitectura objetivo y calidad continua)

- Implementar estructura hexagonal por módulos.
- Incorporar Flyway/Liquibase + política de cambios schema.
- Añadir pruebas de integración Java-Python end-to-end con fixtures.
- Definir SLOs y dashboard (latencia bridge, ratio retries, errores parse, drift).

---

# 8) Quick Wins (1-2 días)

1. Corregir `engine_indicators.py` para consumir MessagePack o `IndicatorsService` para enviar JSON (elegir una y aplicar en ambos lados).
2. Fix inmediato de `DataSerializationUtils` para no cerrar `OutputStream` externo.
3. Ajustar `PathConfig.existeModelo` + `engine_predict.py` naming.
4. Quitar `passwordHash` de `Usuario.toString`.
5. Corregir `ToggleStrategy` para no hacer `print` a stdout.
6. Añadir validación startup de contrato de estrategia (métodos abstractos requeridos).
7. Limpiar `pom.xml` de dependencia dotenv duplicada.

---

# 9) Riesgos residuales y métricas de seguimiento

## Riesgos residuales

- Persistencia de drift entre SQL manual y JPA si no se adopta migración versionada.
- Fallos intermitentes del bridge por cambios en scripts Python sin contrato formal.
- Riesgo de regresión funcional al desacoplar `AppBot` si no hay suite de smoke tests CLI.
- Posibles deadlocks/esperas largas por lock pesimista bajo alta concurrencia sin métricas.

## Métricas recomendadas

- `bridge_protocol_error_rate` (% mensajes inválidos Java<->Python).
- `python_process_timeout_rate` por motor (`fetch`, `indicators`, `train`, `rt`, `backtest`).
- `retry_count_distribution` por caso de uso.
- `accounting_invariant_violations` (`reservado + comprometido + disponible`).
- `schema_drift_findings` por release.
- `mean_fetch_recovery_time` tras fallo en incremental.
- `active_strategy_state_mismatch` (estado DB vs proceso activo en memoria).
- `model_resolution_fail_rate` (train/predict/trade).

---

# 10) Ejecucion inmediata (10 cambios esta semana)

Objetivo: reducir fallos de produccion en Java-Python, estabilizar datos y eliminar incoherencias criticas de negocio sin una migracion grande.

1. Unificar protocolo de indicadores en MessagePack end-to-end.
   Archivo: `code/scripts/engine_indicators.py`
   Archivo: `code/backendBotTrading/src/main/java/com/bottrading/services/IndicatorsService.java`
   Criterio de aceptacion: `cbi` y `train` no fallan por parseo y los indicadores se guardan para un simbolo de prueba.

2. Corregir cierre prematuro de streams en serializacion por chunks.
   Archivo: `code/backendBotTrading/src/main/java/com/bottrading/utils/DataSerializationUtils.java`
   Criterio de aceptacion: cargas grandes no rompen pipe ni quedan truncadas.

3. Arreglar semantica de `riesgo_abierto` (porcentaje vs monto).
   Archivo: `code/backendBotTrading/src/main/java/com/bottrading/services/PaperTradingService.java`
   Archivo: `code/backendBotTrading/src/main/java/com/bottrading/services/AccountingService.java`
   Criterio de aceptacion: abrir/cerrar posicion deja `riesgo_abierto` consistente y auditable.

4. Endurecer validacion de integridad en `fetch` para evitar falsos "al dia".
   Archivo: `code/backendBotTrading/src/main/java/com/bottrading/services/FetchService.java`
   Archivo: `code/backendBotTrading/src/main/java/com/bottrading/repositories/VelaRepository.java`
   Criterio de aceptacion: si hay huecos recientes, forzar resync desde gap detectado.

5. Alinear naming de artefactos de modelo entre train/predict/CLI.
   Archivo: `code/scripts/engine_train.py`
   Archivo: `code/scripts/engine_predict.py`
   Archivo: `code/backendBotTrading/src/main/java/com/bottrading/utils/PathConfig.java`
   Criterio de aceptacion: modelo entrenado se puede cargar en prediccion y trade sin renombrados manuales.

6. Corregir ruta unica de resultados de backtest.
   Archivo: `code/scripts/engine_backtest.py`
   Archivo: `code/backendBotTrading/src/main/java/com/bottrading/services/FileService.java`
   Criterio de aceptacion: resultados quedan en un solo arbol (`code/results/...`) y se listan desde Java.

7. Corregir contrato de estrategias Python.
   Archivo: `code/strategies/BaseStrategy.py`
   Archivo: `code/strategies/AITraderStrategy.py`
   Archivo: `code/strategies/ScalpingRSIStrategy.py`
   Archivo: `code/strategies/StressTestStrategy.py`
   Archivo: `code/strategies/ToggleStrategy.py`
   Criterio de aceptacion: no hay `TypeError` al instanciar estrategias en train/predict/rt.

8. Limpiar SQL base y alinear con JPA.
   Archivo: `info/tablas.sql`
   Archivo: `code/backendBotTrading/src/main/java/com/bottrading/beans/Vela.java`
   Criterio de aceptacion: script ejecuta sin errores, sin tablas duplicadas ni FKs rotas.

9. Reducir riesgos de seguridad y ruido de salida.
   Archivo: `code/backendBotTrading/src/main/java/com/bottrading/beans/Usuario.java`
   Archivo: `code/strategies/ToggleStrategy.py`
   Criterio de aceptacion: no se imprime `passwordHash` ni se contamina stdout de protocolos.

10. Consolidar configuracion y dependencias duplicadas.
    Archivo: `code/backendBotTrading/pom.xml`
    Archivo: `code/backendBotTrading/src/main/resources/META-INF/application.properties`
    Archivo: `code/backendBotTrading/src/main/java/com/bottrading/BotApplication.java`
    Criterio de aceptacion: una sola fuente de verdad para `ddl-auto/show-sql` y una sola libreria dotenv.

## Orden recomendado de ejecucion

1. Cambios 1, 2, 3, 4 (estabilidad runtime y datos).
2. Cambios 5, 6, 7 (coherencia funcional de IA y estrategias).
3. Cambios 8, 9, 10 (hardening de base, seguridad y config).

## Resultado esperado al final de la semana

- `train`, `cbi`, `fetch`, `trade` sin errores de protocolo.
- Datos de velas sin huecos no detectados en ventana reciente.
- Modelos reutilizables entre entrenamiento y prediccion.
- Menor tasa de errores intermitentes en Java-Python y trazabilidad mejorada.
