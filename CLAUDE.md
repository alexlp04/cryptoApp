# CLAUDE.md

Guía de trabajo para Claude Code en CryptoApp. Todo lo aquí descrito está verificado
contra el código real de `main`.

> **Aviso:** `.github/copilot-instructions.md` y `.github/agents/*.agent.md` describen una
> arquitectura **obsoleta** (TSV, `pandas-ta`, PyTorch, `poblar_indicadores`, `engine_paper_trade.py`).
> No los uses como fuente de verdad. Este archivo y el código mandan.

## Qué es el proyecto

Plataforma de trading algorítmico sobre cripto (TFG, Universidad de Murcia). Java orquesta
(CLI, sesión, persistencia, contabilidad, ciclo de vida de procesos); Python calcula
(fetch, indicadores, backtest, entrenamiento, optimización, inferencia RT). Paper trading
únicamente — nunca dinero real.

## Stack real

| Capa | Tecnología |
|---|---|
| Backend | Java 21, Spring Boot 3.3.5, Maven, Lombok |
| CLI | Implementación propia en `interfaces/cli/` — **no** es Spring Shell |
| Persistencia | Spring Data JPA + MySQL 8; H2 en tests; `JdbcTemplate` para inserciones masivas de velas |
| Esquema | **Flyway** (`src/main/resources/db/migration`); Hibernate en `ddl-auto=validate` |
| Validación de datos | pandera (contratos OHLCV), hypothesis (tests de propiedades) |
| IPC | `msgpack-core` + `jackson-dataformat-msgpack` (Java) ↔ `msgpack` (Python) |
| Analítica | pandas, numpy, **`ta`** (no `pandas-ta`), scikit-learn, xgboost, lightgbm |
| Deep learning | **TensorFlow/Keras** (no PyTorch) |
| Optimización | Optuna |
| Calidad | Checkstyle + PMD (Java); ruff + mypy + pytest (Python) |

## Estructura

```
code/backendBotTrading/src/main/java/com/bottrading/
  ├── {market,trading,training,strategy,wallet,user,backtesting}/   # dominios hexagonales
  │     └── {application,domain,infrastructure}/
  ├── interfaces/cli/commands/    # comandos de la CLI
  ├── config/  shared/
code/scripts/       # engines Python (entrypoints invocados por Java)
code/strategies/    # BaseStrategy.py + estrategias concretas
models/ results/ logs/   # artefactos generados — no versionar
```

Cada dominio sigue arquitectura hexagonal: `domain` no depende de `infrastructure`.

## Comandos

```bash
# Backend
cd code/backendBotTrading && mvn spring-boot:run
mvn test          # JUnit + H2
mvn verify        # incluye Checkstyle y PMD

# Python
pytest code/scripts/tests -q
ruff check code/
mypy code/scripts
```

El intérprete se resuelve según el SO (`AppConstants.resolveDefaultPythonExecutable`):
`.venv/Scripts/python.exe` en Windows, `.venv/bin/python3` en POSIX. Override:
`CRYPTOAPP_PYTHON`.

## Esquema de base de datos

Lo versiona **Flyway**, no `ddl-auto=update`. La baseline vive en
`src/main/resources/db/migration/V1__baseline_schema.sql`; `info/tablas.sql` quedó
como referencia histórica y **está desactualizado** (le faltan `instancia_estrategia.nombre_modelo`
y `posicion.{precio_salida, pnl, fecha_cierre}`). No lo uses como fuente de verdad.

Al añadir o cambiar un campo de una entidad, crea una migración `V<n>__descripcion.sql`.
`SchemaMigrationValidationTest` falla si entidades y migraciones divergen — los tests
unitarios no pueden detectarlo porque generan el esquema desde las propias entidades.

```bash
mvn test -Dtest=SchemaMigrationValidationTest        # entidades vs migraciones (H2/MySQL mode)
mvn test -Dtest=EntitySchemaDumpTest -Ddump.schema=true   # volcar esquema de entidades
```

Los tests con `@SpringBootTest` **se cuelgan**: `AppBot` implementa `CommandLineRunner`
y arranca la CLI interactiva. Usa `@DataJpaTest` para tests de persistencia.

## Contrato IPC (crítico)

Dos protocolos coexisten y **no deben mezclarse**:

**1. Request/response — MessagePack con framing.** Usado por fetch, indicators, backtest,
train, optimize, predict. Sobre stdin/stdout binario:

```
[4 bytes big-endian = longitud del body][body msgpack]
```

El body es un envelope con exactamente estas claves:
```python
{"protocol_version": "1.0", "message_type": <IpcMessageType>, "correlation_id": <uuid>, "payload": {...}}
```

`message_type` debe existir en `IpcMessageType.java`. Java: `IpcMessagePackCodec`.
Python: `ipc_protocol.py` (`read_request_payload`, `write_response`, `write_error`).

**2. Tiempo real — líneas de texto en stdout.** Usado por `engine_rt.py` / `engine_ai_rt.py`:

| Línea | Significado |
|---|---|
| `SIGNAL\t<json>` | Señal de trading → parseada a `SignalDTO` |
| `HEARTBEAT...` | Liveness; alimenta el watchdog de inactividad |
| cualquier otra | Log, se registra como `PYLOG` |

Parser: `SignalProtocolParser.java`. JSON malformado se ignora, no aborta la sesión.

Reglas: **siempre** `flush=True` al emitir; nunca escribir texto suelto en stdout dentro de
un engine con framing (corrompe el frame); los logs van a stderr vía `setup_engine_logging()`.

## Contrato de estrategias

`code/strategies/BaseStrategy.py` — abstractos obligatorios:

```python
populate_indicators(df) -> pd.DataFrame   # añade columnas; no elimines filas
should_buy(row: dict) -> bool             # evalúa UNA vela
should_sell(row: dict) -> bool
get_stop_loss(entry_price, row) -> float
get_take_profit(entry_price, row) -> float
```

Concretos ya provistos (sobreescribibles): `get_warmup_period()`, `get_position_size()`,
`should_close()`, `get_feature_columns()`, `get_label()`, `max_open_trades()`.

Puntos que suelen malinterpretarse:
- `WARMUP_PERIOD` y `LABEL_RETURN_THRESHOLD` son atributos de clase; ajústalos al timeframe.
- `get_label()` es **forward-looking a propósito** (usa la vela `t+1`). No lo redefinas a
  partir de `should_buy`/`should_sell`: el modelo memorizaría la regla (F1 ≈ 99% sin valor real).
- El capital usa `Decimal`, no `float`.
- Las features de contexto (ATR, ROC, Bollinger) alimentan al modelo aunque no las use
  `should_buy` — dan señales ortogonales.

## Reglas de código

**Python** — `flush=True` siempre en stdout; logs a stderr; `.itertuples()` nunca
`.iterrows()`; sin lookahead bias (para la señal en `t` solo datos hasta `t`); NaN en
OHLCV es dato corrupto → error explícito, pero NaN inicial de un indicador con ventana
es esperado; claves API solo por entorno.

**Java** — `BigDecimal` para todo lo financiero, nunca `double`; inyección por constructor
con campos `final`, nunca `@Autowired` en campo; `Instant.ofEpochMilli()` para timestamps
de Binance (epoch ms, UTC); virtual threads para I/O (`Executors.newVirtualThreadPerTaskExecutor()`);
`JdbcTemplate.batchUpdate()` para velas, JPA solo para entidades de bajo volumen.

## Commits

Máximo dos líneas, formato `feat|fix|wip|docs|data: <resumen>`.
**Nunca** añadas el trailer `Co-Authored-By: Claude` — se purgó del historial deliberadamente.
