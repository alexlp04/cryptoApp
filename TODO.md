# TODO Tecnico - Ejecucion Inmediata

Checklist accionable derivado de `plan.md`.
Sin estimaciones. Ordenado por prioridad tecnica y dependencias.

## Fase A - Estabilidad runtime y datos (hacer primero)

- [ ] Unificar protocolo de indicadores Java-Python en MessagePack end-to-end
  - Archivos:
    - `code/scripts/engine_indicators.py`
    - `code/backendBotTrading/src/main/java/com/bottrading/services/IndicatorsService.java`
  - Done cuando:
    - `cbi` no falla por parseo
    - `train` no rompe en etapa de indicadores
    - No aparece `MismatchedInputException` en logs

- [ ] Corregir cierre prematuro de streams en serializacion por chunks
  - Archivos:
    - `code/backendBotTrading/src/main/java/com/bottrading/utils/DataSerializationUtils.java`
  - Done cuando:
    - cargas grandes no rompen pipe
    - no hay truncamiento por `Stream closed`

- [ ] Corregir semantica de `riesgo_abierto` (monto vs porcentaje)
  - Archivos:
    - `code/backendBotTrading/src/main/java/com/bottrading/services/PaperTradingService.java`
    - `code/backendBotTrading/src/main/java/com/bottrading/services/AccountingService.java`
  - Done cuando:
    - abrir/cerrar posicion deja `riesgo_abierto` consistente
    - no crece de forma espuria tras cierres

- [ ] Endurecer validacion de integridad en `fetch` para evitar falsos "al dia"
  - Archivos:
    - `code/backendBotTrading/src/main/java/com/bottrading/services/FetchService.java`
    - `code/backendBotTrading/src/main/java/com/bottrading/repositories/VelaRepository.java`
  - Done cuando:
    - detecta huecos recientes
    - fuerza resincronizacion desde gap

## Fase B - Coherencia funcional IA y estrategia

- [ ] Alinear naming de artefactos de modelo entre train/predict/CLI
  - Archivos:
    - `code/scripts/engine_train.py`
    - `code/scripts/engine_predict.py`
    - `code/backendBotTrading/src/main/java/com/bottrading/utils/PathConfig.java`
  - Done cuando:
    - modelo entrenado se puede cargar para prediccion/trade sin renombre manual

- [ ] Unificar ruta de resultados de backtest
  - Archivos:
    - `code/scripts/engine_backtest.py`
    - `code/backendBotTrading/src/main/java/com/bottrading/services/FileService.java`
  - Done cuando:
    - todos los resultados quedan en `code/results/...`
    - Java los encuentra y lista correctamente

- [ ] Corregir contrato de estrategias Python
  - Archivos:
    - `code/strategies/BaseStrategy.py`
    - `code/strategies/AITraderStrategy.py`
    - `code/strategies/ScalpingRSIStrategy.py`
    - `code/strategies/StressTestStrategy.py`
    - `code/strategies/ToggleStrategy.py`
  - Done cuando:
    - no hay `TypeError` por metodos abstractos faltantes
    - estrategias cargan en train/predict/rt

## Fase C - Hardening de base, seguridad y config

- [ ] Limpiar SQL base y alinear con JPA
  - Archivos:
    - `info/tablas.sql`
    - `code/backendBotTrading/src/main/java/com/bottrading/beans/Vela.java`
  - Done cuando:
    - no hay tablas duplicadas ni FK invalidas
    - constraint unico coincide con nombres reales de columnas

- [ ] Reducir riesgos de seguridad y ruido de salida
  - Archivos:
    - `code/backendBotTrading/src/main/java/com/bottrading/beans/Usuario.java`
    - `code/strategies/ToggleStrategy.py`
  - Done cuando:
    - `toString` de usuario no expone hash
    - estrategias no contaminan stdout de protocolo

- [ ] Consolidar configuracion y dependencias duplicadas
  - Archivos:
    - `code/backendBotTrading/pom.xml`
    - `code/backendBotTrading/src/main/resources/META-INF/application.properties`
    - `code/backendBotTrading/src/main/java/com/bottrading/BotApplication.java`
  - Done cuando:
    - una sola dependencia dotenv
    - `ddl-auto/show-sql` definidos en una sola fuente de verdad

## Dependencias entre tareas

- La tarea de protocolo de indicadores debe cerrarse antes de validar flujo completo de `train`.
- La tarea de streams en `DataSerializationUtils` debe cerrarse antes de pruebas de carga de fetch/indicators.
- La tarea de naming de modelos debe cerrarse antes de endurecer validaciones CLI de `trade`.
- La tarea SQL/JPA debe cerrarse antes de hardening final de despliegue.

## Smoke tests de cierre (checklist final)

- [ ] `fetch -tf 1h -coins BTCUSDT` completa sin excepciones de parseo
- [ ] `cbi -tf 1h -coins BTCUSDT` calcula y guarda indicadores
- [ ] `train -m random_forest -s RSISMAStrategy -c BTCUSDT -t 1h -d 7` termina sin error
- [ ] `trade` encuentra modelo entrenado con naming final
- [ ] no hay errores de contrato Java-Python en logs
