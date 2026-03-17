# Reporte Fase 2 (parcial 4) - 2026-03-17

## Resumen
Se completó un cuarto bloque de desacoplamiento de `AppBot`, migrando los comandos residuales de wallets, consultas y sistema al patrón `Command`.

Comandos extraídos en este bloque:
- `mkpwallet`
- `lw`
- `le`
- `ls`
- `lsa`
- `lsd`
- `lst`
- `models`
- `cbi`

Con este avance, `AppBot` queda reducido a responsabilidades de shell loop, dispatch por registry y salida ordenada.

## Alcance ejecutado
Se crearon handlers dedicados para los comandos residuales y se eliminó la lógica legacy equivalente de `AppBot`.

## Cambios realizados

### 1) Nuevos comandos CLI
Archivos nuevos:
- `code/backendBotTrading/src/main/java/com/bottrading/interfaces/cli/commands/CreatePaperWalletCommand.java`
- `code/backendBotTrading/src/main/java/com/bottrading/interfaces/cli/commands/ListWalletsCommand.java`
- `code/backendBotTrading/src/main/java/com/bottrading/interfaces/cli/commands/ListStrategyFilesCommand.java`
- `code/backendBotTrading/src/main/java/com/bottrading/interfaces/cli/commands/ListStrategiesCommand.java`
- `code/backendBotTrading/src/main/java/com/bottrading/interfaces/cli/commands/ListActiveStrategiesCommand.java`
- `code/backendBotTrading/src/main/java/com/bottrading/interfaces/cli/commands/ListStoppedStrategiesCommand.java`
- `code/backendBotTrading/src/main/java/com/bottrading/interfaces/cli/commands/ListTerminatedStrategiesCommand.java`
- `code/backendBotTrading/src/main/java/com/bottrading/interfaces/cli/commands/ModelsCommand.java`
- `code/backendBotTrading/src/main/java/com/bottrading/interfaces/cli/commands/CbiCommand.java`

Cobertura migrada:
- Validacion de sesion para comandos sensibles.
- Operaciones de wallet y listados de estrategias.
- Catalogo textual de modelos IA.
- Calculo de indicadores por simbolo (`cbi`).

### 2) Limpieza estructural de `AppBot`
Archivo modificado:
- `code/backendBotTrading/src/main/java/com/bottrading/AppBot.java`

Cambios:
- Registro de los 9 comandos nuevos en `initializeCommandRegistry()`.
- Eliminados `case` legacy correspondientes del switch.
- Eliminados metodos legacy de comando:
  - `crearWallet(...)`
  - `listarWallets()`
  - `listarFicherosDeEstrategias()`
  - `listarEstrategias()`
  - `listarEstrategiasActivas()`
  - `listarEstrategiasDetenidas()`
  - `listarEstrategiasTerminadas()`
  - `conseguirDatos(...)`
  - `validarLogin()`
  - `mostrarMenuModelos()`

Resultado:
- `AppBot` deja de actuar como clase de negocio y se consolida como coordinador de CLI.

### 3) Correccion de compilacion durante el bloque
Archivo modificado:
- `code/backendBotTrading/src/main/java/com/bottrading/interfaces/cli/commands/BacktestCommand.java`

Detalle:
- Se encapsuló en `try/catch` la llamada a `estrategiaService.ejecutarBacktest(...)` para manejar la excepcion checked declarada por el servicio.

## Validacion ejecutada

### Compilacion backend
Comando:
- `cd code/backendBotTrading && mvn -DskipTests compile`

Resultado final:
- `BUILD SUCCESS`.

## Estado del TODO
Archivo:
- `plan/TODO.md`

Actualizacion:
- Se añadió "Avance parcial 4 (2026-03-17)" en la tarea de Fase 2 con el detalle de comandos residuales extraídos.

## Siguiente paso recomendado (cierre de Fase 2)
- Introducir `CliInputValidator` para unificar validaciones y mensajes de error.
- Revisar si quedan ramas de control en `AppBot` que deban pasar a capas `interfaces/application` para cerrar criterio de fase.
