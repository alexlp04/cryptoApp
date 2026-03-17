
# Auditoría Arquitectónica — BotTrading

## Resumen Ejecutivo
El proyecto está funcional y ya resolvió varias incidencias críticas de IPC y consistencia de datos, pero mantiene deuda técnica arquitectónica alta en orquestación, separación de capas y estandarización de contratos Java↔Python. El mayor riesgo actual está en el acoplamiento CLI-negocio (`AppBot`), la dispersión de gestión de procesos Python entre servicios y la semántica financiera todavía ambigua de `riesgo_abierto`. En persistencia, la mezcla JPA/JDBC está bien encaminada para volumen, pero persisten puntos de precisión y gobernanza de schema. Prioridad recomendada: desacoplar comandos, unificar bridge/protocolo y cerrar invariantes de dominio monetario.

## Índice de Hallazgos por Severidad
| Clase/Componente | Problema | Severidad (🔴 Alta / 🟡 Media / 🟢 Baja) |
|---|---|---|
| `code/backendBotTrading/src/main/java/com/bottrading/AppBot.java` | God Class CLI + validación + orquestación de negocio + UX interactiva | 🔴 Alta |
| `code/backendBotTrading/src/main/java/com/bottrading/services/TradingService.java` | Mezcla gestión proceso Python, parsing protocolo, retries, colas, lifecycle y cierre contable | 🔴 Alta |
| `code/backendBotTrading/src/main/java/com/bottrading/services/EstrategiaService.java` | 6 dependencias, mezcla lifecycle, consultas, backtest y file orchestration | 🔴 Alta |
| `code/backendBotTrading/src/main/java/com/bottrading/services/AccountingService.java` | `riesgo_abierto` sigue operando con parámetro `risk` ambiguo (ratio vs monto) | 🔴 Alta |
| `code/scripts/engine_backtest.py` + `code/backendBotTrading/src/main/java/com/bottrading/services/BacktestingService.java` | Contrato aún JSON, sin protocolo versionado común con resto MessagePack | 🔴 Alta |
| `code/scripts/engine_train.py` + `code/scripts/engine_predict.py` + `code/scripts/engine_rt.py` + `code/scripts/engine_ai_rt.py` | IPC heterogéneo (JSON lineal, prints, payloads no tipados) | 🔴 Alta |
| `code/backendBotTrading/src/main/java/com/bottrading/services/SessionManager.java` | `logout()` potencial NPE (`currentUser.getNombre()`) | 🔴 Alta |
| `code/backendBotTrading/src/main/java/com/bottrading/services/MarketDataService.java` | `CompletableFuture.allOf(...).join()` sin timeout/cancelación | 🟡 Media |
| `code/backendBotTrading/src/main/java/com/bottrading/services/AITrainingService.java` | Duplicación de lógica de proceso/timeout/stream respecto a otros servicios | 🟡 Media |
| `code/backendBotTrading/src/main/java/com/bottrading/services/BacktestingService.java` | Duplicación de gestión proceso Python; comentario de MessagePack no coincide con implementación JSON | 🟡 Media |
| `code/backendBotTrading/src/main/java/com/bottrading/services/FileService.java` | Persistencia CSV mezclada con lógica de negocio de stats; locking vía `synchronized` global | 🟡 Media |
| `code/backendBotTrading/src/main/java/com/bottrading/services/PaperTradingService.java` | Formatos no robustos para analítica (`win_rate` string `%`, `fecha_fin` con `Date.toString`) | 🟡 Media |
| `code/backendBotTrading/src/main/java/com/bottrading/repositories/InstanciaEstrategiaRepository.java` | Agregado monetario como `Double` (`sumCapitalActivoByWallet`) | 🟡 Media |
| `code/backendBotTrading/src/main/java/com/bottrading/beans/InstanciaEstrategia.java` | Estado de dominio como `String` (primitive obsession) | 🟡 Media |
| `code/backendBotTrading/src/main/java/com/bottrading/services/StatsCache.java` | Scheduler propio fuera de infraestructura de scheduling de Spring | 🟡 Media |
| `code/backendBotTrading/src/main/java/com/bottrading/beans/CapitalReservado.java` | Modelo duplicado conceptualmente con campos de `InstanciaEstrategia`; baja cohesión | 🟡 Media |
| `code/backendBotTrading/src/main/java/com/bottrading/services/WalletService.java` | Dependencia directa de sesión en capa de servicio (acoplamiento contextual) | 🟢 Baja |
| `code/backendBotTrading/src/main/java/com/bottrading/utils/CommandParser.java` | Parsing posicional frágil y difícil de extender | 🟢 Baja |
| `code/backendBotTrading/src/main/java/com/bottrading/utils/PythonEnvironmentValidator.java` | Validación de entorno útil pero fuera de health subsystem estandarizado | 🟢 Baja |
| `info/tablas.sql` | DDL manual sin framework de migraciones versionadas | 🟢 Baja |

## 1. Clases que deben eliminarse o fusionarse
- `code/backendBotTrading/src/main/java/com/bottrading/beans/CapitalReservado.java`:
  - Razón: duplica responsabilidad de capital reservado/comprometido/riesgo que ya vive en `InstanciaEstrategia` y `AccountingService`.
  - Propuesta: eliminar entidad y consolidar el agregado monetario en `InstanciaEstrategia` + ledger en `AccountingService`.
- `code/backendBotTrading/src/main/java/com/bottrading/services/FileService.java` método `guardarResultadosCompletos(...)`:
  - Razón: marcado `@Deprecated` y contradice el flujo actual (trades en streaming desde Python).
  - Propuesta: eliminar método y mantener solo path de estadísticas backtest.
- `code/backendBotTrading/src/main/java/com/bottrading/services/AITrainingService.java` + `code/backendBotTrading/src/main/java/com/bottrading/services/BacktestingService.java` + partes de `TradingService`/`FetchService`/`IndicatorsService`:
  - Razón: lógica repetida de proceso Python (start, write stdin, wait timeout, read stdout/stderr, retry, destroy).
  - Propuesta: fusionar en nuevo componente transversal `PythonBridgeFacade` con subcomponentes:
    - `PythonProcessGateway`
    - `PythonPayloadCodec`
    - `PythonRetryPolicy`

## 2. Clases con responsabilidades mezcladas (SRP violado)
- Clase actual: `code/backendBotTrading/src/main/java/com/bottrading/AppBot.java`
  - Qué hace: consola interactiva, parsing de comandos, validación de flags, selección de wallet, validación de modelos/estrategias, ejecución de casos de uso.
  - Problema concreto: mezcla presentación CLI con lógica de aplicación en métodos como `ejecutarTrade(...)`, `ejecutarBacktest(...)`, `validarYCalcularDias(...)`.
  - Propuesta:
    - `CliSessionController` (login/logout/signup)
    - `CliTradingCommandHandler` (trade/start/stop/term)
    - `CliTrainingCommandHandler` (train/models)
    - `CliMarketDataCommandHandler` (fetch/cbi)
    - `CliInputValidator` (parseo y validación semántica)
- Clase actual: `code/backendBotTrading/src/main/java/com/bottrading/services/TradingService.java`
  - Qué hace: lifecycle de procesos, cola de reintentos, parseo de protocolo de señales, gestión de errores, cierre contable.
  - Problema concreto: mezcla infraestructura (IPC/proceso) con reglas de aplicación (reintento de señales y cierre).
  - Propuesta:
    - `RealtimeProcessSupervisor`
    - `SignalProtocolParser`
    - `SignalRetryQueueService`
    - `StrategyRuntimeCoordinator`
- Clase actual: `code/backendBotTrading/src/main/java/com/bottrading/services/EstrategiaService.java`
  - Qué hace: start/stop/terminate, consultas por estado, listado de scripts, orchestration de backtest.
  - Problema concreto: capa aplicación sobredimensionada, 6 dependencias, mezcla command/query + IO de archivos.
  - Propuesta:
    - `StrategyLifecycleApplicationService`
    - `StrategyQueryService`
    - `StrategyBacktestApplicationService`
    - `StrategyCatalogService`
- Clase actual: `code/backendBotTrading/src/main/java/com/bottrading/services/FileService.java`
  - Qué hace: persistencia CSV de trades/stats, parsing CSV, limpieza de directorios y lógica de actualización.
  - Problema concreto: infraestructura + semántica de stats agregadas.
  - Propuesta:
    - `TradeCsvWriter`
    - `StatsCsvRepository`
    - `BacktestArtifactsCleaner`

## 3. Clases mal planteadas o con diseño incorrecto
- `code/backendBotTrading/src/main/java/com/bottrading/services/SessionManager.java`:
  - Problema: estado de sesión en memoria + cleanup de estrategias + potencial NPE en `logout()`.
  - Replanteamiento: separar `UserSessionContext` (estado) de `ApplicationShutdownCoordinator` (cleanup).
- `code/backendBotTrading/src/main/java/com/bottrading/repositories/InstanciaEstrategiaRepository.java`:
  - Problema: `sumCapitalActivoByWallet` retorna `Double` para dinero.
  - Replanteamiento: retornar `BigDecimal` y propagar precisión exacta.
- `code/backendBotTrading/src/main/java/com/bottrading/beans/InstanciaEstrategia.java`:
  - Problema: estado como `String` (`ACTIVA/DETENIDA/TERMINADA`) sin máquina de transiciones.
  - Replanteamiento: `enum StrategyState` + validador de transición.
- `code/backendBotTrading/src/main/java/com/bottrading/services/PaperTradingService.java`:
  - Problema: serialización de métricas en formatos textuales poco fiables para analítica.
  - Replanteamiento: guardar `win_rate` como `BigDecimal` y timestamps ISO-8601 UTC.
- `code/scripts/engine_ai_rt.py`:
  - Problema: carga de modelo y decisión de inferencia mezcladas con IO websocket/stream.
  - Replanteamiento: separar en `ModelRuntimeAdapter`, `RealtimeSignalLoop` y `SignalEmitter`.

## 4. Patrones de diseño aplicables
- Patrón: `Command`
  - Dónde aplicarlo: `code/backendBotTrading/src/main/java/com/bottrading/AppBot.java`
  - Beneficio: elimina `switch` monolítico, facilita testing por comando.
  - Ejemplo:
```java
public interface CliCommand {
    String name();
    void execute(CommandContext ctx);
}
public final class TradeCommand implements CliCommand { ... }
```
- Patrón: `Facade`
  - Dónde aplicarlo: infraestructura Java↔Python (`TradingService`, `FetchService`, `IndicatorsService`, `AITrainingService`, `BacktestingService`).
  - Beneficio: contrato único de proceso/retry/timeout/codec.
  - Ejemplo:
```java
public interface PythonBridgeFacade {
    <TReq, TRes> TRes execute(PythonCall<TReq, TRes> call);
}
```
- Patrón: `Strategy + Registry`
  - Dónde aplicarlo: resolución de estrategias/modelos por nombre (`PathConfig`, scripts dinámicos).
  - Beneficio: elimina validaciones dispersas y centraliza descubrimiento.
  - Ejemplo:
```java
public interface StrategyMetadataProvider { StrategyMetadata get(String name); }
```
- Patrón: `Template Method`
  - Dónde aplicarlo: flujos repetidos de ejecución de proceso Python.
  - Beneficio: estandariza lifecycle y reduce duplicación.
  - Ejemplo:
```java
abstract class AbstractPythonUseCase<TReq,TRes> {
    public final TRes run(TReq req){ start(); write(req); waitExit(); return read(); }
}
```
- Patrón: `Observer / Domain Events`
  - Dónde aplicarlo: señal procesada en `PaperTradingService` y persistencia de stats/ledger.
  - Beneficio: desacopla ejecución de señal de side-effects (CSV/estadísticas/auditoría).
  - Ejemplo:
```java
record TradeClosedEvent(Long instanciaId, BigDecimal pnl) {}
```
- Patrón: `State`
  - Dónde aplicarlo: `InstanciaEstrategia.estado`.
  - Beneficio: evita transiciones inválidas y strings mágicos.

## 5. Reestructuración de capas
Estructura actual (simplificada):
```text
CLI (AppBot)
  -> Services (mezcla application + infra)
      -> Repositories + Python scripts + CSV
```

Estructura propuesta:
```text
interfaces.cli
  - CliShell
  - commands/*Command

application
  - usecase/strategy/*
  - usecase/marketdata/*
  - usecase/training/*

core.domain
  - model (InstanciaEstrategia, Wallet, Posicion)
  - policy (RiskPolicy, StateTransitionPolicy)
  - events

infrastructure.persistence
  - jpa.repositories
  - jdbc.batch (candles)
  - file.csv

infrastructure.python
  - PythonBridgeFacade
  - codecs (JsonCodec, MessagePackCodec)
  - process (timeout/retry/lifecycle)
```
Clases a mover:
- `AppBot` -> dividir en `interfaces.cli.commands.*`
- `FileService` -> `infrastructure.persistence.file.csv`
- `PythonProcessSupport`/partes de services -> `infrastructure.python.*`
- lógica de transición estado de `EstrategiaService` -> `core.domain.policy`

## 6. Problemas de persistencia y queries
- `code/backendBotTrading/src/main/java/com/bottrading/repositories/InstanciaEstrategiaRepository.java`:
  - `sumCapitalActivoByWallet` devuelve `Double`; para importes monetarios debe ser `BigDecimal`.
- `code/backendBotTrading/src/main/java/com/bottrading/services/FetchService.java`:
  - Correcta decisión de usar `JdbcTemplate.batchUpdate` para velas masivas.
  - Riesgo residual: batch size fijo alto (`50000`) sin backpressure/adaptive sizing según memoria/latencia DB.
- `code/backendBotTrading/src/main/java/com/bottrading/services/AITrainingService.java`:
  - Carga de `velas` + `indicadores` en memoria completa; riesgo de presión RAM para ventanas largas.
- `code/backendBotTrading/src/main/java/com/bottrading/beans/InstanciaEstrategia.java`:
  - `@ElementCollection(fetch = FetchType.EAGER)` en `simbolos`; puede penalizar consultas de listados.
- `info/tablas.sql`:
  - Mejorado, pero sigue manual; falta Flyway/Liquibase para gobernanza de cambios.
- N+1 latente:
  - No se observa un N+1 crítico activo en las rutas inspeccionadas, pero existe riesgo en lecturas de `InstanciaEstrategia` con `simbolos` EAGER y potenciales relaciones `ManyToOne` sin fetch plan explícito.

## 7. Problemas de concurrencia y gestión de procesos
- `TradingService`, `FetchService`, `IndicatorsService`, `AITrainingService`, `BacktestingService` crean ejecutores propios:
  - Falta centralización en beans de `Executor` y política única de shutdown.
- `MarketDataService.actualizarDatosMercado`:
  - `CompletableFuture.allOf(...).join()` sin timeout ni cancelación de tareas colgadas.
- `StatsCache`:
  - Scheduler interno propio (`ScheduledExecutorService`) fuera de `@Scheduled`; lifecycle más frágil.
- Buen punto:
  - uso de virtual threads en `TradingService`/`MarketDataService` reduce costo de bloqueo IO.
- Riesgo operativo:
  - cada servicio define sus propios timeouts/retries; comportamiento global inconsistente bajo fallos.

## 8. Protocolo IPC Java↔Python
Inconsistencias detectadas:
- MessagePack:
  - `engine_fetch.py` y `engine_indicators.py` usan binario MessagePack.
  - Java equivalente en `FetchService` e `IndicatorsService`.
- JSON por stdin/stdout:
  - `engine_train.py`, `engine_predict.py`, `engine_backtest.py`, `engine_rt.py`, `engine_ai_rt.py`.
- Señales runtime:
  - `TradingService` acepta `SIGNAL\t<json>` y también fallback `json` crudo.

Problemas:
- Contrato no versionado ni tipado globalmente.
- No hay handshake de capacidades (codec, versión, esquema).
- Coexisten estilos de framing diferentes (stream binario, stdout lineal, prints JSON).

Propuesta de estandarización:
- Definir `protocol_version`, `message_type`, `correlation_id`, `payload`.
- Unificar framing: `length-prefixed MessagePack` para todos los engines de alto volumen.
- Mantener adaptador JSON solo para compatibilidad temporal.
- Añadir tests contract-first (Java y Python) por motor: fetch, indicators, train, predict, rt, backtest.

## 9. Plan de acción priorizado
- [CRÍTICO] Extraer `AppBot` a comandos (`Command`) y mover validación/orquestación fuera de CLI — Esfuerzo estimado: 3-4 días
- [CRÍTICO] Implementar `PythonBridgeFacade` y eliminar duplicación de gestión de procesos Python — Esfuerzo estimado: 4-6 días
- [CRÍTICO] Cerrar semántica de `riesgo_abierto` con `riskAmount` monetario e invariantes contables — Esfuerzo estimado: 1-2 días
- [CRÍTICO] Estandarizar contrato IPC versionado y framing único — Esfuerzo estimado: 3-5 días
- [ALTO] Introducir `StrategyState` enum + validador de transiciones — Esfuerzo estimado: 1-2 días
- [ALTO] Sustituir agregados monetarios `Double` por `BigDecimal` (`sumCapitalActivoByWallet`) — Esfuerzo estimado: 0.5 día
- [ALTO] Añadir timeout/cancelación a `MarketDataService` en paralelismo por símbolo — Esfuerzo estimado: 0.5-1 día
- [ALTO] Separar `FileService` en adapters CSV (trade/stats/cleaner) — Esfuerzo estimado: 2-3 días
- [MEDIO] Migrar scheduler de `StatsCache` a infraestructura Spring (`@Scheduled`) — Esfuerzo estimado: 0.5-1 día
- [MEDIO] Sustituir formatos textuales de métricas (`win_rate`, `fecha_fin`) por tipos normalizados — Esfuerzo estimado: 0.5 día
- [MEDIO] Configurar Flyway/Liquibase e inicializar baseline desde `tablas.sql` — Esfuerzo estimado: 1-2 días
- [BAJO] Endurecer parser CLI (errores tipados, validación central) — Esfuerzo estimado: 1 día
- [BAJO] Consolidar health checks de entorno (`EnvironmentValidator` + Python) en endpoint/runner único — Esfuerzo estimado: 0.5 día

## 10. Estructura de paquetes propuesta
```text
code/backendBotTrading/src/main/java/com/bottrading/
  interfaces/
    cli/                         # Entrada por terminal, parsing y comandos
      CliShell.java
      commands/                  # Command pattern por caso de uso
  application/
    strategy/                    # Casos de uso de ciclo de vida de estrategias
    marketdata/                  # Casos de uso fetch + indicadores
    training/                    # Casos de uso train/predict
    backtest/                    # Casos de uso backtest
  domain/
    model/                       # Entidades de dominio y VOs (sin detalles infra)
    policy/                      # Reglas de riesgo, transiciones de estado
    events/                      # Eventos de dominio
  infrastructure/
    persistence/
      jpa/                       # Repositorios y mapeos JPA
      jdbc/                      # Inserciones batch de alto volumen
      filecsv/                   # Persistencia de trades/stats en CSV
    python/
      bridge/                    # Facade y ejecución de procesos
      codec/                     # JSON/MessagePack codecs
      protocol/                  # Mensajes versionados y contrato IPC
  config/                        # Beans de ejecutores, scheduling, resiliencia
  exceptions/                    # Excepciones de aplicación/infra tipadas
```
---
