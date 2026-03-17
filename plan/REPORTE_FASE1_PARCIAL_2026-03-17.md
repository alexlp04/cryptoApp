# Reporte Fase 1 (parcial) - 2026-03-17

## Resumen
Se avanzó la Fase 1 con dos bloques ya implementados:
1. `PythonBridgeFacade` creada y aplicada a los servicios objetivo.
2. Contrato IPC versionado v1 definido en Java y documentado.

## 1) PythonBridgeFacade aplicada

### Nuevos archivos
- `code/backendBotTrading/src/main/java/com/bottrading/bridge/PythonBridgeFacade.java`
- `code/backendBotTrading/src/main/java/com/bottrading/bridge/PythonBridgeRequest.java`
- `code/backendBotTrading/src/main/java/com/bottrading/bridge/PythonBridgeExecutionException.java`

### Servicios migrados
- `code/backendBotTrading/src/main/java/com/bottrading/services/FetchService.java`
- `code/backendBotTrading/src/main/java/com/bottrading/services/IndicatorsService.java`
- `code/backendBotTrading/src/main/java/com/bottrading/services/BacktestingService.java`
- `code/backendBotTrading/src/main/java/com/bottrading/services/AITrainingService.java`
- `code/backendBotTrading/src/main/java/com/bottrading/services/TradingService.java`

### Cobertura funcional centralizada
- arranque uniforme de procesos Python
- timeout configurable por request
- retries con backoff
- drenado de `stderr`
- cleanup/destrucción forzada
- helpers para procesos de larga vida (runtime trading)

## 2) Contrato IPC versionado v1

### Nuevos archivos
- `code/backendBotTrading/src/main/java/com/bottrading/bridge/protocol/IpcEnvelope.java`
- `code/backendBotTrading/src/main/java/com/bottrading/bridge/protocol/IpcMessageType.java`
- `code/backendBotTrading/src/main/java/com/bottrading/bridge/protocol/IpcProtocol.java`
- `code/docs/IPC_CONTRATO_V1.md`

### Campos obligatorios definidos
- `protocol_version`
- `message_type`
- `correlation_id`
- `payload`

### Estado de adopción
- Java: contrato modelado y disponible.
- Python engines: adopción iniciada con helper común `code/scripts/ipc_protocol.py`.
- Compatibilidad: se mantiene fallback legacy para no romper ejecución actual durante la transición.

## 3) Framing MessagePack length-prefixed (avance)

### Nuevos componentes
- `code/backendBotTrading/src/main/java/com/bottrading/bridge/protocol/IpcMessagePackCodec.java`
- `code/scripts/ipc_protocol.py`

### Engines migrados a envelope framed
- `code/scripts/engine_train.py`
- `code/scripts/engine_backtest.py`
- `code/scripts/engine_predict.py`
- `code/scripts/engine_fetch.py`
- `code/scripts/engine_indicators.py`

### Runtime (RT) endurecido
- `code/scripts/engine_rt.py` y `code/scripts/engine_ai_rt.py` emiten señales con formato único `SIGNAL\t<json>`.
- `code/backendBotTrading/src/main/java/com/bottrading/services/TradingService.java` elimina parsing dual de JSON crudo en stdout.

### Servicios Java adaptados al framing
- `code/backendBotTrading/src/main/java/com/bottrading/services/AITrainingService.java`
- `code/backendBotTrading/src/main/java/com/bottrading/services/BacktestingService.java`
- `code/backendBotTrading/src/main/java/com/bottrading/services/FetchService.java`
- `code/backendBotTrading/src/main/java/com/bottrading/services/IndicatorsService.java`

## 4) TODO actualizado
- Se marcó como completada la tarea:
  - `Crear PythonBridgeFacade para centralizar start/timeout/retry/stdout-stderr/cleanup`
- Se marcó como completada la tarea:
  - `Definir contrato IPC versionado comun`
- Se marcó como completada la tarea:
  - `Estandarizar framing unico para motores Python (objetivo: MessagePack length-prefixed)`

Archivo modificado:
- `TODO.md`

## 5) Validación
Comandos ejecutados:
- `cd code/backendBotTrading && mvn -q -DskipTests compile`
- `python -m pytest -q code/scripts/tests/test_engine_indicators_ipc.py code/scripts/tests/test_engine_ipc_error_contracts.py`
- `runTests` para `src/test/java/com/bottrading/bridge/protocol/IpcMessagePackCodecTest.java`

Resultado:
- Compilación completa sin errores.
- Sin errores de sintaxis en scripts Python modificados (validado con Pylance).
- Tests Python IPC: `4 passed`.
- Test Java codec IPC: `passed`.

## Pendiente de Fase 1
- Completar tests contract-first para `fetch` y runtime (`rt`, `ai_rt`) en escenarios de integración controlados.
