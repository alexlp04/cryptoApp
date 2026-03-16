# Cambios realizados desde el último commit

Este documento resume las modificaciones detectadas en el repositorio respecto al último commit disponible en Git. El objetivo es explicar qué se ha tocado, por qué bloque funcional cae cada cambio y qué impacto aparente tiene.

## Resumen general

Los cambios se concentran en seis áreas principales:

1. Ajustes de configuración de agentes de Copilot.
2. Refuerzo del arranque Java y resolución de rutas/proceso Python.
3. Evolución del pipeline de entrenamiento IA para soportar estrategias dinámicas.
4. Migración parcial del intercambio Java-Python hacia MessagePack.
5. Ampliación del contrato base de estrategias Python.
6. Incorporación de documentación, inventarios y artefactos de auditoría.

Además, se han eliminado dos ficheros antiguos de contexto y se ha movido el SQL de referencia a una ubicación nueva dentro de `info/`.

## 1. Cambios en agentes de Copilot

Se han modificado tres definiciones de agentes en `.github/agents/`:

- `AGENTE_BRIDGE.agent.md`
- `AGENTE_DOCS.agent.md`
- `AGENTE_PYTHON.agent.md`

### Qué cambia

- Se amplía la lista de herramientas disponibles para estos agentes.
- `AGENTE_DOCS` pasa de un conjunto mínimo (`Read, Grep, Glob`) a un conjunto mucho más amplio, incluyendo edición, búsqueda, terminal, web, Pylance, Sonar y depuración Java.

### Impacto

- El cambio no afecta a la lógica de negocio del sistema, pero sí al comportamiento operativo del entorno de asistencia dentro de VS Code.
- En `AGENTE_BRIDGE` y `AGENTE_PYTHON` la línea `tools:` queda aparentemente duplicada al final, lo que sugiere una edición posiblemente defectuosa del campo YAML y conviene revisarla antes de depender de esos agentes.

## 2. Arranque de la aplicación y validación del entorno Python

### Ficheros afectados

- `code/backendBotTrading/src/main/java/com/bottrading/BotApplication.java`
- `code/backendBotTrading/src/main/java/com/bottrading/utils/AppConstants.java`
- `code/backendBotTrading/src/main/java/com/bottrading/utils/EnvironmentValidator.java`
- `code/backendBotTrading/src/main/java/com/bottrading/utils/PythonEnvironmentValidator.java` (nuevo)
- `code/backendBotTrading/src/main/java/com/bottrading/utils/PythonProcessSupport.java`
- `code/backendBotTrading/src/main/java/com/bottrading/utils/PathConfig.java`

### Qué cambia

- En el arranque de Spring Boot se añade una validación explícita del entorno Python antes de continuar con la inicialización.
- La constante del ejecutable Python deja de ser `python3` y pasa a apuntar al virtualenv del proyecto: `.venv/bin/python3`.
- La carga del `.env` ya no busca específicamente en `./backendBotTrading`, sino en la raíz actual `./`.
- Se introduce una nueva utilidad, `PythonEnvironmentValidator`, que comprueba:
  - que el ejecutable Python exista,
  - que la versión sea al menos 3.11,
  - que estén instalados módulos críticos como `pandas`, `numpy`, `sklearn`, `xgboost`, `lightgbm`, `tensorflow`, `optuna` y `joblib`.
- `PythonProcessSupport` pasa a resolver rutas absolutas para el ejecutable Python y para los scripts, y además fija el directorio de trabajo del proceso en la raíz del proyecto.
- `PathConfig` se adapta a la estructura real del workspace, asumiendo que el código Python está dentro de `code/`.

### Impacto

- El sistema se vuelve más estricto al arrancar: ahora fallará pronto si el virtualenv o las dependencias Python no están disponibles.
- También se reduce la dependencia del `PATH` global del sistema.
- La resolución de rutas queda más coherente con la estructura actual del repositorio, especialmente para localizar `scripts/`, `strategies/` y `models/` dentro de `code/`.

## 3. Cambios en CLI y entrenamiento IA desde Java

### Ficheros afectados

- `code/backendBotTrading/src/main/java/com/bottrading/AppBot.java`
- `code/backendBotTrading/src/main/java/com/bottrading/services/AITrainingService.java`
- `code/backendBotTrading/src/main/java/com/bottrading/utils/StrategyInspector.java` (nuevo)

### Qué cambia en `AppBot`

- Se actualiza el texto de ayuda del comando `train` para admitir `-strategy <nombre>`.
- La llamada a `aiTrainingService.entrenarModelo(...)` ahora pasa también el nombre de estrategia.
- Hay además pequeños retoques de indentación en varias llamadas y en `mostrarMenuModelos()`.

### Qué cambia en `AITrainingService`

- El servicio de entrenamiento acepta ahora un parámetro adicional `strategyName`.
- Se introduce un flujo dual:
  - flujo legado, basado en velas más indicadores persistidos en base de datos,
  - flujo dinámico, basado en estrategia Python, donde se construye un dataset OHLCV puro y la estrategia genera sus propios features.
- Cuando hay estrategia dinámica:
  - se calcula el `warmup` necesario,
  - se calcula el número total de velas requeridas,
  - se ajustan los días de preparación de datos,
  - se añade al payload enviado a Python `strategy_name` y `warmup_candles`.
- Se añade `resolveCandlesPerDay()` para convertir timeframes como `1m`, `5m`, `15m`, `1h`, `4h`, `1d` a su densidad diaria.

### Qué hace `StrategyInspector`

- Es una utilidad nueva para consultar desde Java metadatos de una estrategia Python sin acoplar el backend a la implementación interna de cada estrategia.
- Ejecuta Python para:
  - instanciar la estrategia,
  - leer su `warmup period`,
  - calcular cuántas velas son necesarias para entrenar con un timeframe y un número de días determinados.

### Impacto

- Se abre la puerta a entrenar modelos directamente sobre features generadas por las estrategias Python, no solo sobre indicadores precalculados y persistidos.
- Esto hace el entrenamiento más flexible y acerca la lógica de feature engineering al dominio de la estrategia.

## 4. Cambios en serialización e intercambio Java-Python

### Ficheros afectados

- `code/scripts/engine_fetch.py`
- `code/scripts/engine_indicators.py`
- `code/backendBotTrading/src/main/resources/logback-spring.xml`

### Cambios en `engine_fetch.py`

- Se reorganiza el script y se introduce `from __future__ import annotations`.
- El fetch deja de imprimir líneas TSV por `stdout` una a una y pasa a:
  - descargar velas paginadas,
  - transformarlas a diccionarios serializables,
  - empaquetarlas en un único payload MessagePack,
  - escribir ese binario en `sys.stdout.buffer`.
- Se añaden constantes nuevas como `REQUEST_TIMEOUT_SECONDS = 20` y `BINANCE_LIMIT = 1000`.
- Los valores decimales pasan a serializarse como `str` en lugar de `float`, evitando coerciones y pérdidas de precisión al otro lado.

### Cambios en `engine_indicators.py`

- El script incorpora `msgpack`.
- La salida deja de ser JSON texto por `print` y pasa a emitirse en MessagePack binario por `stdout.buffer`.
- En caso de error o entrada vacía, devuelve una lista vacía empaquetada en MessagePack en vez de imprimir `[]`.

### Cambio en `logback-spring.xml`

- Se renombra el fichero de log general de `app-general.log` a `app-general.logs`.
- Se renombra el fichero de errores de `app-errors.log` a `errores.log`.
- También se actualizan los nombres de los archivos rotados en `archived/`.

### Impacto

- El bridge Java-Python se orienta claramente a un protocolo binario más eficiente que JSON/TSV.
- El cambio del fetch es relevante porque modifica totalmente el formato de salida esperado por Java.
- El cambio de logs afecta a la nomenclatura de los ficheros generados en disco y puede requerir adaptar scripts externos que los consuman.

## 5. Cambios en el motor Python de entrenamiento

### Fichero afectado

- `code/scripts/engine_train.py`

### Qué cambia

- Se añade carga dinámica de estrategias mediante `importlib`.
- Se incorpora `load_strategy(strategy_name)` para:
  - importar la estrategia solicitada,
  - comprobar que existe la clase esperada,
  - validar que hereda de `BaseStrategy`.
- Se añade `apply_strategy_features(...)`, que:
  - ordena el dataset por timestamp,
  - llama a `populate_indicators()` de la estrategia,
  - aplica `warmup`,
  - construye `X` e `y`,
  - elimina `NaN` e infinitos,
  - exige un mínimo de filas limpias.
- El script soporta dos modos:
  - modo clásico, con target binario basado en el cierre de la siguiente vela,
  - modo dinámico, donde las etiquetas se generan desde la propia estrategia.
- Se guardan metadatos adicionales en el modelo entrenado:
  - `feature_cols`,
  - `strategy_name`,
  - `warmup_candles`.
- Para modelos deep learning, además del `.keras`, se genera un fichero `.metadata.json` asociado.
- Cuando se usa estrategia dinámica, el nombre del modelo guardado incluye también el nombre de la estrategia.
- Las métricas de precisión, recall y F1 pasan a calcularse con media ponderada en el caso multiclase del modo dinámico.

### Impacto

- El entrenamiento deja de estar limitado a un problema binario simple y puede adaptarse a salidas tipo `buy`, `sell`, `hold` según defina la estrategia.
- Se mejora la trazabilidad del modelo entrenado al adjuntar metadatos sobre features y estrategia usada.

## 6. Cambios en la jerarquía de estrategias Python

### Ficheros afectados

- `code/strategies/BaseStrategy.py`
- `code/strategies/RSISMAStrategy.py`

### Qué cambia en `BaseStrategy`

- Se amplía de forma importante el contrato base.
- Se añaden:
  - `WARMUP_PERIOD`,
  - tipado en el constructor,
  - métodos abstractos `get_stop_loss()` y `get_take_profit()`,
  - métodos concretos `get_warmup_period()`, `get_name()`, `should_close()`, `max_open_trades()`, `get_feature_columns()` y `get_label()`.
- `get_warmup_period()` inspecciona constantes `*_PERIOD` de la clase para derivar automáticamente el warmup requerido.

### Qué cambia en `RSISMAStrategy`

- Se fija explícitamente `WARMUP_PERIOD = 20`.
- Se refactoriza el cálculo del RSI y la SMA.
- Se tipan `should_buy()` y `should_sell()`.
- Se añaden implementaciones de `get_stop_loss()` y `get_take_profit()` con niveles fijos del 2% y 4% respectivamente.

### Impacto

- La base de estrategias queda preparada para entrenamiento dinámico, etiquetado y gestión más rica de posiciones.
- `RSISMAStrategy` pasa a ajustarse al contrato nuevo y a poder proporcionar más información operativa que antes.

## 7. Cambios de documentación, auditoría e inventario

### Ficheros nuevos o modificados

- `plan.md` (nuevo)
- `requirements.txt` (nuevo)
- `code/audit_hashes.txt` (nuevo)
- `code/audit_scan.txt` (nuevo)
- `code/audit_scan2.txt` (nuevo)
- `code/info_files.txt` (nuevo)
- `code/java_files.txt` (nuevo)
- `code/py_files.txt` (nuevo)
- `code/resource_files.txt` (nuevo)

### Qué contienen

- `plan.md` recoge una auditoría extensa del proyecto, con hallazgos, riesgos, prioridades, quick wins y plan de trabajo.
- `requirements.txt` define dependencias Python para datos, ML, DL, Binance, websockets, serialización y tooling.
- Los ficheros `audit_*` e índices de archivos parecen ser artefactos de una exploración o auditoría técnica del código:
  - hashes por fichero,
  - conteos por categoría,
  - índices Java/Python,
  - patrones de riesgo transversales,
  - inventarios de rutas.

### Impacto

- No introducen lógica de ejecución del producto, pero sí aumentan la trazabilidad y documentación interna del estado del proyecto.
- Algunos de estos archivos parecen generados automáticamente y podrían no ser necesarios en el repositorio final si solo se usan como apoyo temporal.

## 8. Reorganización de SQL y borrado de contexto anterior

### Ficheros eliminados

- `code/context/CONTEXTO_GENERAL.md`
- `code/context/creacion_tablas.sql`

### Fichero nuevo relacionado

- `info/tablas.sql`

### Qué cambia

- Se elimina un documento de contexto general del sistema.
- Se elimina el SQL que estaba en `code/context/creacion_tablas.sql`.
- Ese SQL aparece de nuevo como archivo nuevo en `info/tablas.sql`, con el mismo contenido base.

### Impacto

- Parece un movimiento de reorganización documental: el SQL deja de estar en `code/context/` y pasa a `info/`.
- No hay evidencia de que el contenido del SQL haya sido corregido funcionalmente durante el traslado; el cambio visible es de ubicación, no de diseño.

## 9. Cambios menores o cosméticos

- En `AppBot.java` hay varios ajustes de indentación y formato sin cambio funcional evidente.
- En `RSISMAStrategy.py` también hay una pequeña limpieza de formato y alineación visual.

## 10. Conclusión

Desde el último commit, la línea de trabajo principal parece haber sido la siguiente:

- endurecer la ejecución del backend respecto al entorno Python,
- adaptar rutas y procesos a la estructura real del proyecto,
- mover el intercambio Java-Python hacia MessagePack,
- permitir entrenamiento IA guiado por estrategias dinámicas Python,
- ampliar el contrato base de las estrategias,
- y documentar el estado técnico del sistema con una auditoría bastante detallada.

En términos prácticos, los cambios más relevantes son los que afectan a:

- validación del virtualenv y dependencias,
- resolución de rutas del proyecto,
- protocolo de comunicación entre Java y Python,
- y pipeline de entrenamiento con `strategy_name`.

Si hace falta, se puede generar una segunda versión de este documento más orientada a revisión técnica, con formato de checklist por fichero o separando cambios funcionales, estructurales y puramente documentales.