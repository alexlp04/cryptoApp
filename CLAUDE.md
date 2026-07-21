# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

CryptoApp is an **interactive CLI** (not a REST API) for crypto algo-trading, built as a TFG. A **Java 21 + Spring Boot** process orchestrates the session, persistence, accounting and strategy lifecycle; **Python 3.11 engines** do the heavy compute (Binance fetch, indicators, backtest, ML train/optimize, realtime inference). The two runtimes talk over a custom stdin/stdout IPC. Today it does paper trading only — there is **no real-order execution code** (the `is_real` flag exists but is inert).

## Repository layout

```
code/backendBotTrading/   # Java backend + CLI + Maven tests  (~160 classes)
code/scripts/             # Python engines: engine_{fetch,indicators,backtest,rt,ai_rt,train,optimize}.py
code/strategies/          # Python strategies loaded dynamically (BaseStrategy subclasses)
models/  results/  logs/  # Trained model artifacts, backtest/optimize CSVs, runtime logs (repo root)
info/tablas.sql           # Base MySQL schema
```

## Commands

Run everything from `code/backendBotTrading/` for Java. Toolchain is pinned in `code/mise.toml` (Java temurin-21, Python 3.11).

**Java (Maven):**
```bash
mvn spring-boot:run                    # launch the interactive CLI (needs MySQL + .env)
mvn clean package                      # build the jar
mvn test                               # unit tests only (H2, fast)
mvn -Dtest=FetchServiceTest test       # single test class
mvn -Dtest=FetchServiceTest#should_download_and_persist_velas_when_symbol_is_valid test  # single method
mvn verify                             # tests + Checkstyle + PMD + SpotBugs + OWASP dependency-check + JaCoCo
```
Quality gates (Checkstyle, PMD, SpotBugs, dependency-check, JaCoCo report) run in the `verify` phase, not `test`.

**Python (from `code/`, with the venv active):**
```bash
pytest scripts/tests/                              # all Python tests
pytest scripts/tests/test_ipc_protocol.py -k roundtrip   # single test
ruff check scripts strategies                      # lint
mypy scripts                                       # type check
```

**One-time setup** (see root `README.md` for full detail): create `.venv` + `pip install -r requirements.txt`; symlink the venv where Java expects it — `ln -sfn ../../.venv code/backendBotTrading/.venv`; create `code/backendBotTrading/.env` with `DB_URL`, `DB_USER`, `DB_PASSWORD`.

## Architecture — the big picture

### Two-runtime split, one process tree
Java never computes indicators or runs models itself. It spawns Python engines and exchanges data over stdin/stdout. **All Python launches must go through `PythonBridgeFacade`** (`trading/infrastructure/bridge/`) — never `new ProcessBuilder(...)` directly. `PathConfig` auto-detects the project root by walking up until it finds `code/backendBotTrading`, so engine paths resolve regardless of the working directory.

### The IPC has TWO different channels — do not conflate them
This is the subtlest part of the codebase, and the README/Copilot docs oversimplify it as "MessagePack binary IPC" everywhere. Reality:

1. **Batch request/response** (fetch, indicators, backtest, train, optimize): framed **MessagePack** — `[4-byte big-endian length][msgpack body]`. Java writes with `IpcMessagePackCodec.writeEnvelope()`, Python reads with `ipc_protocol.read_request_payload()` and replies with `write_response()`. Driven by `PythonBridgeFacade.execute(PythonBridgeRequest)`.
2. **Realtime signal stream** (`engine_rt.py`, `engine_ai_rt.py`): **NDJSON text**, not MessagePack. Python emits `SIGNAL\t{json}` lines on stdout; Java reads them line-by-line in `StrategyRuntimeCoordinator` via `SignalProtocolParser.parseLineaLog()`, then hands each to `PaperTradingService.onSignal()`. Config is still sent Java→Python as framed MessagePack once at startup. Python logs go to **stderr** (never stdout) to keep the signal channel clean.

Realtime lifecycle: `StrategyRuntimeCoordinator` (orchestration, virtual-thread per strategy) → `RealtimeProcessSupervisor` (OS process CRUD) → `SignalRetryQueueService` (in-memory retry, max 5 consecutive failures). Note: retry state is in-memory and the retry queue is only drained on shutdown; there is no supervised auto-restart if the Python process dies — relevant when hardening for 24/7.

### Hexagonal, feature-first — NOT layer-first
Packages are organized **by bounded context first, then by hexagonal layer**:
`com.bottrading.<context>.{application,domain,infrastructure}` where `<context>` ∈ `market, strategy, trading, training, wallet, user, backtesting`, plus `shared/`, `config/`, `interfaces/cli/`.
⚠️ The layer-first tree in `.github/copilot-instructions.md` (`com.bottrading/application/`, `/domain/`, `/infrastructure/`) is **outdated** — trust the actual `<context>/application` layout, e.g. `market/application/FetchService.java`, `trading/application/PaperTradingService.java`. Dependency rule holds: `domain` → nothing; `application` → `domain` + ports; `infrastructure` implements `application` ports; `interfaces/cli` → `application` only.

### Strategy plugin system
Strategies are Python classes extending `BaseStrategy` (`code/strategies/`), loaded dynamically **by file path** at runtime. Abstract contract to implement: `populate_indicators`, `should_buy`, `should_sell`, `get_stop_loss`, `get_take_profit`. `WARMUP_PERIOD` / `*_PERIOD` fields drive how many candles buffer before signals fire. Strategy filenames are validated against path traversal in `PathConfig.getValidStrategyPath`. The same strategy runs in backtest, training (for labels), and realtime.

### CLI
`AppBot` (a `CommandLineRunner`) runs an interactive `Scanner` loop — **not Spring Shell**. Each `*Command` in `interfaces/cli/commands/` parses args and **delegates to an application service**; no business logic in commands. Typical flow: `signup → login → mkpwallet → fetch → le → backtest → train → optimize → trade`.

## Conventions that bite if ignored

- **`BigDecimal` for every financial value** (price, volume, capital, PnL, fees). Never `double`/`float`. But `VelaDTO` intentionally carries prices as **`String`** for IPC serialization — convert with `new BigDecimal(dto.getClose())` when computing.
- **Timestamps are `Long` epoch-millis** in JPA entities (`BIGINT UNSIGNED` in MySQL, never `DATETIME`); MySQL price columns are `DECIMAL(20,8)`.
- **Soft delete only.** Entities extend `BaseEntity` (`id`, `fechaCreacion`, `eliminado`); set `eliminado = true` and filter `eliminado = false` — never physical `DELETE`. `Usuario` is the exception (plain POJO, no JPA).
- **Bulk persistence** (>~1000 rows) uses `JdbcTemplate.batchUpdate` with `ON DUPLICATE KEY UPDATE`, not `repository.saveAll`.
- **DI by constructor** (`@RequiredArgsConstructor` or explicit `final` fields); `@Autowired` on fields is disallowed. Logging via Lombok `@Slf4j`, never `System.out`.
- **Method naming:** domain actions use Spanish verbs (`ejecutarBacktest`, `detenerEstrategia`); technical operations use English (`execute`, `onSignal`, `fetch`). Entities are Spanish singular nouns; DTOs suffix `DTO`; ports suffix `UseCase` (in) / `Port` (out); adapters suffix `Adapter`.
- **Exceptions** extend `TradingServiceException` (`DataFetchException`, `PythonProcessException`, `ValidationException`). No empty/generic catch without re-throw.

## Detailed conventions reference

`.github/copilot-instructions.md` has fuller examples of test structure (JUnit 5 `@Nested`/`@DisplayName`, Mockito, `should_x_when_y` naming), IPC message types, and DI/logging patterns. Use it for style detail — but its package-structure section is stale (see the feature-first note above).
