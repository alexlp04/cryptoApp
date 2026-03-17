# TODO Maestro - Resolucion Optima de Hallazgos

Backlog unico para cerrar todos los problemas de `plan.md` con el menor riesgo posible.
Ordenado por ruta critica: primero estabilidad y contratos, luego refactor por capas, luego hardening y gobernanza.

## Fase 0 - Blindaje inmediato (dia 0-1)

- [x] Corregir NPE potencial en `SessionManager.logout()`
  - Archivos: `code/backendBotTrading/src/main/java/com/bottrading/services/SessionManager.java`
  - Criterio de cierre: logout seguro sin `currentUser` y tests unitarios verdes.

- [x] Asegurar timeout y cancelacion en `MarketDataService.actualizarDatosMercado`
  - Archivos: `code/backendBotTrading/src/main/java/com/bottrading/services/MarketDataService.java`
  - Criterio de cierre: no hay bloqueos indefinidos en `allOf(...).join()`.

- [x] Cambiar agregados monetarios de `Double` a `BigDecimal` en repositorio de estrategias
  - Archivos: `code/backendBotTrading/src/main/java/com/bottrading/repositories/InstanciaEstrategiaRepository.java`
  - Criterio de cierre: `sumCapitalActivoByWallet` retorna `BigDecimal` y compila toda la cadena de uso.

## Fase 1 - Unificacion Java-Python (ruta critica)

- [x] Crear `PythonBridgeFacade` para centralizar start/timeout/retry/stdout-stderr/cleanup
  - Archivos: `code/backendBotTrading/src/main/java/com/bottrading/bridge/` (nuevos)
  - Criterio de cierre: `FetchService`, `IndicatorsService`, `BacktestingService`, `AITrainingService`, `TradingService` delegan ejecucion de proceso.

- [x] Definir contrato IPC versionado comun
  - Campos minimos: `protocol_version`, `message_type`, `correlation_id`, `payload`.
  - Criterio de cierre: contrato documentado y validado en Java/Python.

- [x] Estandarizar framing unico para motores Python (objetivo: MessagePack length-prefixed)
  - Archivos: `code/scripts/engine_*.py`, `code/backendBotTrading/src/main/java/com/bottrading/services/*`
  - Criterio de cierre: eliminar parsing dual (`SIGNAL\tjson` + json crudo) en runtime.

- [x] Añadir tests contract-first por engine (`fetch`, `indicators`, `train`, `predict`, `rt`, `backtest`)
  - Criterio de cierre: suite de contratos ejecutable en CI local.

## Fase 2 - Refactor de arquitectura por capas

- [x] Romper `AppBot` en comandos (`Command pattern`) y validacion dedicada
  - Nuevos componentes: `CliSessionController`, `CliTradingCommandHandler`, `CliTrainingCommandHandler`, `CliMarketDataCommandHandler`, `CliInputValidator`.
  - Avance parcial (2026-03-17): extraidos comandos `signup`, `login`, `logout`, `ayuda` a `interfaces/cli/commands` con `CliCommand` + `CliCommandContext`.
  - Avance parcial 2 (2026-03-17): extraidos comandos `trade` y `train`; `AppBot` ya no contiene esos métodos monolíticos.
  - Avance parcial 3 (2026-03-17): extraidos `fetch`, `backtest`, `start`, `stop`, `term`; `AppBot` delega esos flujos al registry de comandos.
  - Avance parcial 4 (2026-03-17): extraidos `mkpwallet`, `lw`, `le`, `ls`, `lsa`, `lsd`, `lst`, `models`, `cbi`; `AppBot` queda como loop + dispatch + shutdown.
  - Avance parcial 5 (2026-03-17): creado `CliInputValidator` y aplicado en comandos CLI para login, sintaxis, argumentos minimos, parseo de IDs y captura de `BigDecimal`/confirmaciones.
  - Avance parcial 6 (2026-03-17): añadidos tests unitarios de `CliInputValidator` y `StartCommand`; `mvn test` verde.
  - Criterio de cierre: cumplido para descomposicion de `AppBot` y validacion dedicada.

- [ ] Dividir `TradingService` por responsabilidades
  - Nuevos componentes: `RealtimeProcessSupervisor`, `SignalProtocolParser`, `SignalRetryQueueService`, `StrategyRuntimeCoordinator`.
  - Criterio de cierre: infraestructura IPC desacoplada de reglas de negocio.

- [ ] Dividir `EstrategiaService` en command/query/lifecycle
  - Nuevos componentes: `StrategyLifecycleApplicationService`, `StrategyQueryService`, `StrategyBacktestApplicationService`, `StrategyCatalogService`.
  - Criterio de cierre: maximo 3-4 dependencias por servicio principal.

- [ ] Separar `FileService` en adapters de infraestructura CSV
  - Nuevos componentes: `TradeCsvWriter`, `StatsCsvRepository`, `BacktestArtifactsCleaner`.
  - Criterio de cierre: eliminar `guardarResultadosCompletos(...)` deprecado.

## Fase 3 - Dominio financiero y estado

- [ ] Cerrar invariantes de `riesgo_abierto` como monto monetario
  - Archivos: `code/backendBotTrading/src/main/java/com/bottrading/services/AccountingService.java`, `code/backendBotTrading/src/main/java/com/bottrading/services/PaperTradingService.java`
  - Criterio de cierre: apertura/cierre de posicion conserva contabilidad exacta.

- [ ] Introducir `StrategyState` enum y politica de transiciones
  - Archivos: `code/backendBotTrading/src/main/java/com/bottrading/beans/InstanciaEstrategia.java`, capa dominio nueva.
  - Criterio de cierre: sin strings magicos y sin transiciones invalidas.

- [ ] Revisar/eliminar `CapitalReservado` por solapamiento con agregado monetario
  - Archivos: `code/backendBotTrading/src/main/java/com/bottrading/beans/CapitalReservado.java`
  - Criterio de cierre: una sola fuente de verdad para capital/riesgo.

- [ ] Normalizar metricas para analitica
  - `win_rate` numerico (`BigDecimal`) y fechas ISO-8601 UTC.
  - Criterio de cierre: salidas parseables sin formatos textuales ambiguos.

## Fase 4 - Persistencia, concurrencia y operaciones

- [ ] Ajustar batch insert de velas a tamano adaptativo o configurable
  - Archivos: `code/backendBotTrading/src/main/java/com/bottrading/services/FetchService.java`
  - Criterio de cierre: sin picos de memoria/latencia en cargas largas.

- [ ] Revisar `@ElementCollection(fetch = FetchType.EAGER)` en `simbolos`
  - Archivos: `code/backendBotTrading/src/main/java/com/bottrading/beans/InstanciaEstrategia.java`
  - Criterio de cierre: plan de fetch explicito y sin penalizacion en listados.

- [ ] Migrar scheduler de `StatsCache` a infraestructura Spring (`@Scheduled`)
  - Archivos: `code/backendBotTrading/src/main/java/com/bottrading/services/StatsCache.java`
  - Criterio de cierre: lifecycle gestionado por Spring y shutdown limpio.

- [ ] Centralizar ejecutores (`Executor` beans) y politica de apagado
  - Criterio de cierre: no hay pools/schedulers dispersos por servicio.

## Fase 5 - Gobernanza y calidad continua

- [ ] Introducir Flyway o Liquibase con baseline desde `info/tablas.sql`
  - Criterio de cierre: schema versionado y reproducible por entorno.

- [ ] Consolidar health checks de entorno (Python + DB + rutas)
  - Criterio de cierre: chequeo unico de readiness/startup.

- [ ] Endurecer parser CLI y errores tipados
  - Archivos: `code/backendBotTrading/src/main/java/com/bottrading/utils/CommandParser.java`
  - Criterio de cierre: comandos invalidos con feedback consistente.

- [ ] Completar cobertura minima y calidad estatica
  - Objetivo: >= 80% en `services/`, sin issues `BLOCKER/CRITICAL` en Sonar.
  - Criterio de cierre: reportes de test y analisis limpios antes de merge.

## Orden de ejecucion recomendado (optimo)

- [x] 1. Fase 0 completa (evita caidas y bloqueos actuales).
- [x] 2. Fase 1 completa (contrato unico Java-Python).
- [ ] 3. Fase 3 parcial (riesgo y estado) en paralelo con Fase 2.
- [ ] 4. Fase 2 completa (desacoplar clases monoliticas).
- [ ] 5. Fase 4 completa (rendimiento y lifecycle).
- [ ] 6. Fase 5 completa (migraciones, health, calidad).

## Checklist de validacion final

- [ ] Flujo `fetch -> cbi -> train -> trade -> backtest` estable sin errores de contrato.
- [ ] Sin NPE en sesion/login/logout.
- [ ] Contabilidad (`capital`, `riesgo_abierto`, `PnL`) consistente con `BigDecimal`.
- [ ] Ningun servicio de negocio gestiona procesos Python de forma ad-hoc.
- [ ] Arquitectura separada en capas: `interfaces`, `application`, `domain`, `infrastructure`.
