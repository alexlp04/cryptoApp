# Reporte Fase 2 (parcial 2) - 2026-03-17

## Resumen
Se completó el segundo bloque de desacoplamiento de `AppBot` extrayendo los comandos de mayor tamaño inicial:
- `trade`
- `train`

Con esto, la lógica de entrada CLI para autenticación, ayuda, trade y train ya vive fuera del switch monolítico principal.

## Alcance ejecutado
Se implementó la migración de `trade` y `train` al patrón `Command`, manteniendo el comportamiento funcional y validando compilación completa.

## Cambios realizados

### 1) Extensión del contexto compartido de comandos
Archivo modificado:
- `code/backendBotTrading/src/main/java/com/bottrading/interfaces/cli/CliCommandContext.java`

Cambios:
- Se añadieron dependencias necesarias para comandos operativos:
  - `EstrategiaService`
  - `WalletService`
  - `AITrainingService`

Impacto:
- Los comandos CLI pueden orquestar casos de uso sin depender de métodos internos de `AppBot`.

### 2) Nuevo comando `trade`
Archivo nuevo:
- `code/backendBotTrading/src/main/java/com/bottrading/interfaces/cli/commands/TradeCommand.java`

Cobertura migrada:
- Parsing y validación de flags.
- Validación de combinación `-model` + `-strategy`.
- Validación de existencia de estrategia/modelo.
- Selección de wallet, cálculo de disponible, captura de capital/riesgo.
- Lanzamiento de bot RT vía `EstrategiaService.iniciarTradeRT(...)`.

Impacto:
- Se desacopla una de las rutas más complejas de `AppBot`.

### 3) Nuevo comando `train`
Archivo nuevo:
- `code/backendBotTrading/src/main/java/com/bottrading/interfaces/cli/commands/TrainCommand.java`

Cobertura migrada:
- Parsing/validación de argumentos de entrenamiento.
- Selección de moneda principal.
- Validación y cálculo de días (incluido warning por riesgo de memoria).
- Ejecución de `AITrainingService.entrenarModelo(...)` e impresión de resultados.

Impacto:
- Se elimina otra pieza grande de orquestación de `AppBot`.

### 4) Limpieza de `AppBot` para comandos migrados
Archivo modificado:
- `code/backendBotTrading/src/main/java/com/bottrading/AppBot.java`

Cambios:
- Registro de `TradeCommand` y `TrainCommand` en `commandRegistry`.
- Eliminados del switch legacy:
  - `case "trade"`
  - `case "train"`
- Eliminados métodos monolíticos:
  - `ejecutarTrade(...)`
  - `ejecutarTrain(...)`
  - `validarYCalcularDias(...)` (movido dentro de `TrainCommand`).

Impacto:
- Reducción adicional de tamaño y acoplamiento en `AppBot`.

## Validación ejecutada

### Compilación backend
Comando:
- `cd code/backendBotTrading && mvn -q -DskipTests compile`

Resultado:
- Compilación completa sin errores.

## Estado del TODO
Archivo:
- `plan/TODO.md`

Actualización:
- Se añadió avance parcial 2 en la tarea de Fase 2 (`AppBot`):
  - comandos `trade` y `train` extraídos.
  - `AppBot` ya no contiene esos métodos.

## Siguiente paso recomendado (Fase 2)
- Extraer los comandos restantes de `AppBot` en el mismo patrón:
  - `fetch`
  - `backtest`
  - `start/stop/term`
- Introducir `CliInputValidator` para centralizar validaciones y terminar de cerrar el criterio de fase.
