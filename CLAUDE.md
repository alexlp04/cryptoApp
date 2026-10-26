# CLAUDE.md

Guía de trabajo para Claude Code en CryptoApp. Todo lo aquí descrito está verificado
contra el código real de `main`.

> **Fuente de verdad:** este archivo y el código. `.github/copilot-instructions.md` se ha
> reducido a un puntero a este documento precisamente para que no vuelvan a divergir; los
> `.github/agents/*.agent.md` que describían la arquitectura antigua (TSV, `pandas-ta`, PyTorch,
> `poblar_indicadores`, `engine_paper_trade.py`) se eliminaron y sus equivalentes correctos
> viven en `.claude/agents/`.

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
  └── resources/db/migration/   # migraciones Flyway — fuente del esquema
code/scripts/       # engines Python (entrypoints invocados por Java)
code/scripts/tests/ # suite pytest de los engines
code/strategies/    # BaseStrategy.py + estrategias concretas
.github/workflows/  # CI
ruff.toml mypy.ini  # configuración de calidad Python
models/ results/ logs/   # artefactos generados — no versionar
```

Cada dominio sigue arquitectura hexagonal: `domain` no depende de `infrastructure`.

## Comandos

```bash
# Backend
cd code/backendBotTrading && mvn spring-boot:run
mvn test          # JUnit + H2 (563 tests)
mvn verify        # incluye Checkstyle y PMD

# Python
pytest code/scripts/tests -q   # 401 tests
ruff check code/               # config en ruff.toml
mypy code/scripts              # config en mypy.ini
```

Las cuatro comprobaciones corren solas en cada PR y en cada push a `main`
(`.github/workflows/ci.yml`), más un job extra que valida las migraciones contra MySQL 8 real.
La auditoría de dependencias (Java contra OSV con `.github/scripts/osv_audit.py` + `pip-audit`) va aparte en
`security-audit.yml`, semanal y bajo demanda: está fuera de `mvn verify` a propósito porque son APIs externas
intermitentes y no deben tumbar un PR. Los avisos Java aceptados, con su motivo, viven en `.github/osv-ignore.txt`.
`ruff` está **fijado a una versión concreta** en `requirements.txt`: su conjunto de reglas por
defecto cambia entre versiones, y sin fijarlo el CI aplicaría un criterio distinto al local.

El intérprete se resuelve según el SO (`AppConstants.resolveDefaultPythonExecutable`):
`.venv/Scripts/python.exe` en Windows, `.venv/bin/python3` en POSIX. Override:
`CRYPTOAPP_PYTHON`.

## Despliegue (Docker, Raspberry Pi 5)

`deploy/` tiene la imagen única (Java + motores Python), el `docker-compose.yml` con MySQL 8 y la guía
(`deploy/README.md`). Decisiones que no deben deshacerse: ningún puerto publicado; la app usa un usuario de MySQL con
permisos solo sobre `bottradingdb`; la clave de firma de modelos entra como secreto de Docker; los modelos son un
volumen (se entrenan en el PC) y las estrategias van dentro de la imagen; las dependencias Python se instalan de
`deploy/requirements.lock` (con hashes, solo ruedas; se regenera con `deploy/lock-requirements.sh` y un test vigila que
siga a `requirements.txt`). TensorFlow no va en la imagen (no hay rueda de la versión fijada para arm64) y
`PythonEnvironmentValidator` solo avisa si falta. Probado en x86
con la pila completa (Flyway V13, registro, login, `fetch` por el motor Python, parada y reinicio con datos);
arm64 no se ha podido ejecutar todavía.

**Reanudación en el arranque** (`CRYPTOAPP_AUTORESUME`, apagada por defecto y activa en el compose): la columna
`instancia_estrategia.reanudar_al_arrancar` se marca al activar o reanudar y se limpia al pausar a mano (`detenerEstrategia`);
el apagado de la aplicación (`pausarPorApagado`) pausa sin limpiarla. Al arrancar, tras reconciliar las ACTIVA huérfanas
(caída brusca), `reanudarMarcadas` reanuda las DETENIDA marcadas, y una que falla no impide las demás. Probado en Docker con
`docker stop` y con SIGKILL.

## API de solo lectura (`interfaces/api/`)

Solo `GET`, JSON, para la UI y el móvil: `/api/v1/{health,status,strategies,strategies/{id}/positions|pnl|ledger,wallets}`
(tabla en `deploy/README.md`). Decisiones que no deben deshacerse: **apagada por defecto**
(`spring.main.web-application-type=none`: la CLI y los tests no abren puerto); se habilita con
`SPRING_MAIN_WEBAPPLICATIONTYPE=servlet` y entonces `CRYPTOAPP_API_TOKEN` (32+ caracteres) es obligatorio, sin él no
arranca; todo exige `Authorization: Bearer` salvo `/health` (solo UP/DOWN, 503 si una estrategia ACTIVA no tiene proceso
o falla la base de datos); escucha en loopback y en Docker el puerto solo se publica en `127.0.0.1`; sin operaciones de
escritura. Un solo token ve todo, también las estrategias de todos los usuarios.

## Esquema de base de datos

Lo versiona **Flyway**, no `ddl-auto=update`. La baseline vive en
`src/main/resources/db/migration/V1__baseline_schema.sql`; las migraciones son la única fuente de verdad del esquema.

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

Concretos ya provistos (sobreescribibles): `get_warmup_period()`, `should_close()`,
`get_feature_columns()`, `get_label()`.

Puntos que suelen malinterpretarse:
- `WARMUP_PERIOD` y `LABEL_RETURN_THRESHOLD` son atributos de clase; ajústalos al timeframe.
- `get_label()` es **forward-looking a propósito** (usa la vela `t+1`). No lo redefinas a
  partir de `should_buy`/`should_sell`: el modelo memorizaría la regla (F1 ≈ 99% sin valor real).
- El capital usa `Decimal`, no `float`.
- `get_stop_loss` es **obligatorio y válido** (positivo y por debajo de la entrada): de él sale el tamaño
  de la posición. Una señal de compra sin stop válido se descarta, no se opera con tamaño fijo.
- Las features de contexto (ATR, ROC, Bollinger) alimentan al modelo aunque no las use
  `should_buy` — dan señales ortogonales.

## Gestión de riesgo (backtest y vivo, mismas reglas)

Como en una plataforma de trading: **`risk_per_trade` es lo que se pierde si salta el stop**, no el tamaño de la
posición (rango válido 0 < r ≤ 0,10). Implementado en `scripts/risk_management.py` (Python) y
`trading/domain/RiskRules.java` + `ExecutionCosts.java` (Java); los dos lados deben decir lo mismo.

- Tamaño: `unidades = equity × risk_per_trade / (entrada − stop)`, con tope del 25 % del equity por posición
  y sin superar el capital reservado (spot, sin apalancamiento). `equity` = reservado + comprometido.
- Riesgo abierto total (`riesgo_abierto`, en **dinero**) ≤ 6 % del equity; si una compra lo supera se rechaza.
- Costes: 0,05 % de slippage en órdenes a mercado y stops, 0,1 % de comisión por lado; el objetivo es una orden
  límite (sin slippage). El PnL es neto de ambos.
- Stop y objetivo se ejecutan con el rango de la vela (`low`/`high`), no solo con el cierre; si una vela toca los dos
  gana el stop. En vivo los vigila el motor Python en cada mensaje del stream (`PositionTracker`) y emite un `SELL`
  con `reason` `STOP` u `TARGET`. Java le pasa las posiciones abiertas (`open_positions`) al (re)arrancar.
- Ejecución en backtest: `execution` `market` (por defecto) o `limit` (orden pasiva al cierre, vive una vela, se llena solo si el precio la atraviesa; sin slippage). Medido en BTC/ETH/SOL con tendencia long-only: en 4h la orden límite rinde menos que mercado (se pierden las entradas que se escapan). No hay modo límite en vivo.
- La señal `BUY` lleva `stop_loss` y `take_profit`; el `SELL`, `reason` (`SIGNAL`, `STOP`, `TARGET`).
- Cortos (simulados): una estrategia pone `allow_short = True` y define `should_short` / `should_cover` (y, si quiere,
  `get_short_stop_loss` / `get_short_take_profit`; por defecto simétricos). Señales `SHORT` (lleva stop por encima y
  objetivo por debajo) y `COVER`; `posicion.lado`. Mismo riesgo y tope del 25 % sobre el nocional, sin apalancamiento,
  liquidación ni funding; vende a mercado con slippage en contra. Un giro (cerrar un largo y abrir un corto en la
  misma vela) son dos señales seguidas. No se admiten con `execution="limit"`. En el modo IA, la predicción -1 abre
  el corto y la 1 lo cubre.
- No existe modo real: `trade -r` y `iniciarTradeRT(isReal=true)` se rechazan porque no hay adaptador de exchange.
  Una orden «real» sin él sería una simulación con otro nombre. Cuando haya adaptador, va detrás de un puerto de
  ejecución y `PaperTradingService` pasa a ser una de sus implementaciones.

## Seguridad (decisiones que no deben deshacerse)

- **Modelos firmados.** `joblib.load` ejecuta código: `save_model` guarda un manifiesto con sha256, versiones de
  librerías y un HMAC; `load_model` rechaza lo que no esté firmado por esta instalación. La clave sale de
  `CRYPTOAPP_MODEL_KEY` o de `~/.config/cryptoapp/model.key` (0600, fuera de `models/`). Un modelo sin firma exige
  reentrenar, o `CRYPTOAPP_ALLOW_UNSIGNED_MODELS=1` si confías en el fichero.
- **Nombres a rutas.** Símbolo (`[A-Z0-9]{2,20}`), timeframe y nombre de modelo o estrategia se validan en la CLI
  (`CommandParser.esSimboloValido`) **y** en la frontera de cada engine (`require_symbol`, `require_timeframe`,
  `require_name`): acaban en nombres de fichero y URLs.
- **Dependencias.** Spring Boot se mantiene en una línea con soporte; `jackson-bom.version`, `log4j2.version` y `tomcat.version` del
  `pom.xml` van por delante del BOM solo para parches GHSA: quítalos cuando Boot gestione una versión igual o mayor.
  Antes de promocionar, consulta las versiones contra OSV (`api.osv.dev/v1/querybatch`). Riesgo aceptado: dos avisos de
  `spring-webmvc` 6.2 (SSE con fragmentos y `XsltView`) solo se corrigen en Spring 7; la API no usa ninguna de las dos
  funciones.

## Consistencia entre backtest y vivo

Un backtest solo vale si el motor en vivo hace lo mismo. Dos herramientas (desde `code/scripts`, sobre un CSV
de velas) lo comprueban antes de confiar en una estrategia:

- `consistency_check.py estrategia.py velas.csv`: compara indicadores, señales y stops con el histórico completo,
  con el prefijo (lookahead: usa el futuro) y con la ventana del buffer RT (un indicador que necesita más velas
  que el buffer sale NaN o distinto en vivo).
- `replay_check.py estrategia.py velas.csv`: pasa velas por `engine_rt.run_symbol` (el código real, con
  trayectoria de precios por vela) y compara las señales con las del backtest. Con un modelo (`ai_model`) hace lo
  mismo con `engine_ai_rt` contra el backtest con predicciones.
- `BacktestParityTest` (Java): seis operaciones (largo y corto; cierre por señal, stop y objetivo) dan en
  `PaperTradingService` el mismo PnL neto que `run_backtest`. Si cambian costes o dimensionado, regenera los
  valores esperados en los dos lados.

Regla de decisión de la IA (backtest = vivo): con un modelo de 3 clases, 1 abre un largo, -1 lo cierra (y abre un
corto si la estrategia los permite) y la predicción neutral no hace nada. Antes el backtest cerraba con cualquier
predicción distinta de 1. Diferencias conocidas que quedan: el motor RT exige probabilidad >= 0,52 en modelos
binarios de scikit-learn y el backtest usa el argmax; el RT omite la predicción si algún feature es NaN y el
backtest los rellena con 0; el SVM final se guarda sin `predict_proba` (`probability=False`), así que en RT decide
sin umbral de confianza, igual que el backtest. Los modelos Keras decodifican su salida con una sola función
(`model_loader.decode_deep_learning`) en backtest y RT; los modelos se cargan desde `model_loader.py`.

Cada modelo entrenado deja en su `.metadata.json` un bloque `provenance` (git, momento, rango de datos, métricas).

Hallazgo: el buffer RT es `max(100, WARMUP_PERIOD)`. Una estrategia sin `WARMUP_PERIOD` (como un cruce de EMA 20/50)
con buffer 100 pierde entradas que el backtest sí hace; con 300 coincide. Fija siempre `WARMUP_PERIOD` al mayor
periodo del indicador más lento (varias veces, para las medias exponenciales).

## Conexión con Binance (REST y WebSocket)

El stream de velas de los dos motores RT lo lleva `rt_stream.kline_events`; el REST de velas recientes,
`shared_utils.fetch_recent_klines`. Qué hacen ante cada fallo (probado con un Binance falso, `tests/test_binance_faults.py`):

- **REST:** reintenta 3 veces red caída, timeouts, 5xx, 429/418 (respeta `Retry-After`) y cuerpos que no son JSON o no
  son una lista; un 4xx (símbolo inválido) falla a la primera. Más de 1000 velas se piden por páginas hacia atrás.
- **WebSocket:** reconecta siempre (1 s al principio, doblando hasta 5 min; se reinicia con datos válidos). Cubre el
  corte de las 24 h de Binance, cierres con error, cortes bruscos y servidor caído. Un stream conectado que no manda
  nada en 120 s se da por muerto. JSON roto, mensajes que no son velas, velas incompletas y velas retrasadas se
  descartan sin reconectar.
- **Huecos:** al (re)conectar se resiembra el buffer por REST y se revisan las velas cerradas que se perdieron: si
  en alguna saltó el stop o el objetivo de la posición abierta, se emite la salida con la fecha de esa vela. Si el REST
  falla al resembrar, se reintenta en la siguiente vela, y mientras el buffer tenga huecos la estrategia **no se evalúa**
  (los indicadores saldrían distintos a los del backtest).
- **No cubierto:** el `fetchRange` de Java (relleno de huecos de la base) tiene su propio manejo de errores, distinto.

## Reglas de código

**Python** — `flush=True` siempre en stdout; logs a stderr; `.itertuples()` nunca
`.iterrows()`; sin lookahead bias (para la señal en `t` solo datos hasta `t`); NaN en
OHLCV es dato corrupto → error explícito, pero NaN inicial de un indicador con ventana
es esperado; claves API solo por entorno; `time.monotonic()` para medir duraciones
(`datetime.now()` es sensible a ajustes de reloj) y `datetime.now(UTC)` cuando la marca
temporal se guarda o se muestra; los campos obligatorios del payload IPC se validan en la
frontera del engine, no se dejan caer como `None` hasta el fondo.

**Java** — `BigDecimal` para todo lo financiero, nunca `double`; inyección por constructor
con campos `final`, nunca `@Autowired` en campo; `Instant.ofEpochMilli()` para timestamps
de Binance (epoch ms, UTC); virtual threads para I/O (`Executors.newVirtualThreadPerTaskExecutor()`);
`JdbcTemplate.batchUpdate()` para velas, JPA solo para entidades de bajo volumen.

## Ramas

Modelo estándar (detalle completo en `CONTRIBUTING.md`):

```
main → epic/<tema> → fix|feat/<nº-issue>-<slug> → epic/<tema> → prod
```

Una rama por issue, con su número delante; vuelve a la épica con `--no-ff`; la épica se verifica
**entera** antes de promocionar a `prod`. Nunca se commitea directo a `main` ni a `prod`.

El motivo de que exista la capa de integración: `fix/c4-durable-retry-queue` y `feat/calidad-y-ci`
estaban ambas en verde por separado y al juntarlas rompieron el build
(`Schema-validation: missing table [senal_fallida_pendiente]`), porque C4 añadió una entidad JPA
sin migración y el test que lo detecta venía en la otra rama.

**Toda entidad JPA nueva o modificada exige su migración** `V<n>__descripcion.sql`.

## Commits

Máximo dos líneas, formato `feat|fix|wip|docs|data|style|merge: <resumen>`.
**Nunca** añadas el trailer `Co-Authored-By: Claude` — se purgó del historial deliberadamente.
