# Reporte Fase 2 (parcial) - 2026-03-17

## Resumen
Se inició la Fase 2 con una primera extracción de `AppBot` hacia patrón `Command` en la capa `interfaces.cli`.
Esta entrega reduce acoplamiento en autenticación/ayuda sin romper flujos de trading.

## Alcance ejecutado
Se desacoplaron del switch monolítico de `AppBot` los comandos:
- `signup`
- `login`
- `logout`
- `ayuda`

El resto de comandos operativos (`trade`, `backtest`, `train`, `fetch`, `start/stop/term`) permanece en `AppBot` para la siguiente iteración de Fase 2.

## Cambios realizados

### 1) Contrato base de comandos CLI
Archivos nuevos:
- `code/backendBotTrading/src/main/java/com/bottrading/interfaces/cli/commands/CliCommand.java`
- `code/backendBotTrading/src/main/java/com/bottrading/interfaces/cli/CliCommandContext.java`

Implementación:
- Se creó interfaz `CliCommand` con `name()` y `execute(String[] parts, CliCommandContext context)`.
- Se creó `CliCommandContext` para compartir dependencias de CLI (scanner, session, usuarioService y salida por consola).

Impacto:
- Base reutilizable para extraer comandos restantes sin duplicar wiring.

### 2) Extracción de comandos de autenticación y ayuda
Archivos nuevos:
- `code/backendBotTrading/src/main/java/com/bottrading/interfaces/cli/commands/SignupCommand.java`
- `code/backendBotTrading/src/main/java/com/bottrading/interfaces/cli/commands/LoginCommand.java`
- `code/backendBotTrading/src/main/java/com/bottrading/interfaces/cli/commands/LogoutCommand.java`
- `code/backendBotTrading/src/main/java/com/bottrading/interfaces/cli/commands/HelpCommand.java`

Implementación:
- Se movió la lógica de `flujoSignup`, `flujoLogin`, `flujoLogout` y `mostrarAyuda` a comandos dedicados.
- Los comandos mantienen el mismo comportamiento funcional y mensajes de CLI.

Impacto:
- `AppBot` deja de concentrar toda la lógica de usuario básica.
- Se reduce tamaño/carga cognitiva del switch principal.

### 3) Registro de comandos en `AppBot`
Archivo modificado:
- `code/backendBotTrading/src/main/java/com/bottrading/AppBot.java`

Implementación:
- Se añadió `commandRegistry` con inicialización en `@PostConstruct`.
- Se añadió dispatch previo al switch:
  - si el comando existe en el registro, se ejecuta y retorna.
  - si no existe, continúa el flujo legacy actual.
- Se eliminaron métodos ya extraídos:
  - `flujoSignup()`
  - `flujoLogin()`
  - `flujoLogout()`
  - `mostrarAyuda()`

Impacto:
- Refactor incremental seguro: convivencia `Command pattern` + comandos legacy.
- Sin cambios funcionales en comandos no migrados.

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
- Se añadió nota de avance parcial en la tarea:
  - `Romper AppBot en comandos (Command pattern) y validacion dedicada`

## Siguiente paso recomendado (Fase 2)
- Extraer comandos operativos de alto acoplamiento:
  - `trade`
  - `train`
  - `fetch`
  - `backtest`
- Introducir `CliInputValidator` para mover validaciones sintácticas/semánticas fuera de `AppBot`.
