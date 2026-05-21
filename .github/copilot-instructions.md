# Copilot Instructions — CryptoApp Trading Bot

## Descripción del proyecto

Plataforma de trading algorítmico de criptomonedas con arquitectura hexagonal.
Java 21 + Spring Boot 3.3 como backend orquestador, Python como motor de cálculo.
Comunicación inter-procesos (IPC) vía **MessagePack binario con framing de 4 bytes big-endian**.
CLI interactiva como interfaz principal (no REST). Persistencia en MySQL (H2 en tests).

---

## Stack tecnológico

| Capa | Tecnología |
|---|---|
| Lenguaje | Java 21 — Virtual Threads, Pattern Matching |
| Framework | Spring Boot 3.3.5 |
| Persistencia | Spring Data JPA + Hibernate 6 + JdbcTemplate (batch masivo) |
| Base de datos | MySQL 8+ (producción), H2 (tests) |
| IPC Java↔Python | MessagePack (`jackson-dataformat-msgpack`) con framing 4-byte big-endian |
| CLI | `CommandLineRunner` + loop interactivo con `Scanner` (NO Spring Shell) |
| Build | Maven 3.9+ |
| Testing | JUnit 5 + Mockito + Hamcrest + `@SpringBootTest` con H2 |
| Calidad | Checkstyle, PMD, SpotBugs, JaCoCo |
| Seguridad | `spring-security-crypto` (BCrypt), `java-dotenv` |
| Logging | SLF4J + Logback (`@Slf4j` de Lombok) |
| Python | 3.11+, pandas, numpy, msgpack, tensorflow/keras, requests |

---

## Estructura de paquetes (arquitectura hexagonal)

```
com.bottrading/
├── application/           # Casos de uso, orquestación de servicios
│   ├── market/            # FetchService, IndicatorsService, MarketDataService
│   ├── strategy/          # EstrategiaService, StrategyBacktestApplicationService,
│   │                      # StrategyCatalogService, StrategyLifecycleApplicationService,
│   │                      # StrategyQueryService
│   ├── trading/           # AccountingService, PaperTradingService
│   ├── training/          # AITrainingService
│   └── user/              # UsuarioApplicationService
│       └── port/
│           ├── in/        # CreateUsuarioUseCase, GetUsuarioUseCase
│           └── out/       # UsuarioRepositoryPort
│
├── domain/                # Entidades JPA, repositorios Spring Data, enums
│   ├── BaseEntity.java    # @MappedSuperclass: id, fechaCreacion, eliminado
│   ├── market/            # Vela, VelaDTO, VelaRepository, IndicadorTecnico, IndicadorTecnicoDTO
│   ├── strategy/          # InstanciaEstrategia, InstanciaEstrategiaRepository
│   ├── trading/           # Posicion, PosicionRepository, LedgerEntry, LedgerRepository, LedgerType
│   ├── user/              # Usuario (POJO puro sin JPA), UsuarioRepository
│   └── wallet/            # Wallet, WalletRepository, WalletType, CapitalReservado
│
├── infrastructure/        # Adaptadores de salida, implementaciones técnicas
│   ├── bridge/            # PythonBridgeFacade, PythonBridgeRequest (builder),
│   │   │                  # RealtimeProcessSupervisor, StrategyRuntimeCoordinator,
│   │   │                  # SignalProtocolParser, SignalRetryQueueService
│   │   └── protocol/      # IpcEnvelope (record), IpcMessagePackCodec,
│   │                      # IpcMessageType (enum), IpcProtocol
│   ├── cache/             # StatsCache (in-memory con flush a disco)
│   ├── persistence/       # FileService, CsvUtilities, StatsCsvRepository,
│   │                      # UsuarioPersistenceAdapter, UsuarioJpaEntity,
│   │                      # UsuarioJpaRepository, UsuarioMapper
│   └── validation/        # SessionManager, UsuarioService, WalletService
│
├── interfaces/
│   └── cli/               # AppBot (CommandLineRunner), *Command.java,
│                          # CliCommandContext, CliInputValidator
│
├── services/              # BacktestingService (puente legacy hacia Python)
├── beans/                 # SignalDTO
├── config/                # ProcessExecutorConfig, ServiceConfiguration
├── exceptions/            # TradingServiceException (base), DataFetchException,
│                          # PythonProcessException, ValidationException
└── utils/                 # AppConstants, CommandParser, PathConfig, ConsoleLoader,
                           # PythonProcessSupport, StrategyInspector, SafeParser
```

**Reglas de dependencia:**
- `domain/` no depende de nada externo (ni Spring, ni infraestructura)
- `application/` depende solo de `domain/` y puertos
- `infrastructure/` implementa los puertos definidos en `application/`
- `interfaces/cli/` depende de `application/`, nunca de `infrastructure/` directamente

---

## Convenciones de nombres

### Clases Java
- **Entidades JPA**: singular en español, sin sufijo → `Vela`, `Posicion`, `Wallet`, `InstanciaEstrategia`
- **DTOs**: sufijo `DTO` → `VelaDTO`, `SignalDTO`, `IndicadorTecnicoDTO`
- **Repositorios**: sufijo `Repository` → `VelaRepository`, `PosicionRepository`
- **Servicios de aplicación**: sufijo `Service` → `FetchService`, `AccountingService`
- **Servicios compuestos**: prefijo dominio + sufijo descriptivo → `StrategyBacktestApplicationService`
- **Puertos de entrada**: sufijo `UseCase` → `CreateUsuarioUseCase`, `GetUsuarioUseCase`
- **Puertos de salida**: sufijo `Port` → `UsuarioRepositoryPort`
- **Adaptadores**: sufijo `Adapter` → `UsuarioPersistenceAdapter`
- **Comandos CLI**: sufijo `Command` → `FetchCommand`, `TradeCommand`, `BacktestCommand`
- **Excepciones**: sufijo `Exception` extendiendo `TradingServiceException` → `DataFetchException`
- **Enums**: `PascalCase` sin sufijo → `LedgerType`, `WalletType`, `IpcMessageType`

### Métodos Java
- **Lógica de dominio**: verbos en español → `ejecutarBacktest()`, `iniciarTradeRT()`, `detenerEstrategia()`, `listarEstrategias()`
- **Operaciones técnicas**: verbos en inglés → `fetch()`, `execute()`, `onSignal()`, `commitCapital()`, `closeTrade()`
- **Helpers de test privados**: descriptivos en español → `crearVelasTestList()`, `crearSignalTestBUY()`, `crearInstanciaActivaTest()`
- **Repositorios Spring Data**: convención estándar → `findBySymbolAndIntervalOrderByOpenTimeAsc()`

### Variables y campos
- `camelCase` para instancia, `UPPER_SNAKE_CASE` para constantes
- Campos `final` siempre en inyección por constructor
- `BigDecimal` para TODO dato financiero sin excepción
- `Long` (epoch millis) para timestamps de Binance en entidades JPA
- `Instant` en `BaseEntity.fechaCreacion`

---

## Tipos de datos — Reglas críticas

### BigDecimal SIEMPRE para datos financieros
```java
// ✅ CORRECTO
private final BigDecimal precioEntrada;
private final BigDecimal capitalAsignado;
private final BigDecimal volumen;

// ❌ PROHIBIDO — pérdida de precisión garantizada
private double precio;
private float capital;
```

Aplica a: precios (`open`, `high`, `low`, `close`), volúmenes, capital, PnL, riesgo, comisiones.

### VelaDTO usa String para precios (serialización IPC)
```java
// VelaDTO es mutable con getters/setters — precios como String para serialización
public class VelaDTO {
    private String open;
    private String high;
    private String low;
    private String close;
    private String volume;
    // ...
}
// Al operar financieramente: new BigDecimal(vela.getClose())
```

### Timestamps de Binance
```java
// En entidades JPA: Long (epoch millis)
private Long openTime;

// Al necesitar Instant:
Instant.ofEpochMilli(openTime)
```

---

## Protocolo IPC Java ↔ Python

### Framing binario
```
[4 bytes big-endian: longitud del body] [body: MessagePack serializado]
```
- Java escribe con `IpcMessagePackCodec.writeEnvelope()`
- Python lee con `ipc_protocol.read_request_payload()` (soporta framing v1 y legacy)
- Python responde con `ipc_protocol.write_response()`
- Java lee respuesta con `IpcMessagePackCodec.readEnvelope()`

### IpcEnvelope (record)
```java
public record IpcEnvelope(
    String protocolVersion,
    IpcMessageType messageType,
    String correlationId,
    Object payload
) {}
```

### Tipos de mensaje (IpcMessageType)
```
FETCH_REQUEST, FETCH_RESPONSE,
INDICATORS_REQUEST, INDICATORS_RESPONSE,
TRAIN_REQUEST, TRAIN_RESPONSE,
PREDICT_REQUEST, PREDICT_RESPONSE,
BACKTEST_REQUEST, BACKTEST_RESPONSE,
RT_SIGNAL, RT_LOG, ERROR
```

### Cómo lanzar un proceso Python — SIEMPRE con PythonBridgeFacade
```java
// ✅ CORRECTO — único patrón permitido
PythonBridgeRequest request = PythonBridgeRequest.builder("operation-name", "/path/to/script.py")
    .startupTimeoutSeconds(120)
    .inactivityTimeoutSeconds(300)
    .maxRetries(3)
    .stdinWriter(outputStream -> { /* escribir payload MessagePack */ })
    .stdoutReader(inputStream -> { /* leer respuestas */ })
    .onStderrLine(line -> log.warn("[Python] {}", line))
    .build();

pythonBridgeFacade.execute(request);

// ❌ PROHIBIDO — nunca ProcessBuilder directo fuera de bridge/
new ProcessBuilder("python3", "script.py").start();
```

### Scripts Python disponibles
| Script | Propósito |
|---|---|
| `engine_fetch.py` | Descarga velas de Binance API |
| `engine_indicators.py` | Calcula RSI, EMA, SMA sobre dataset recibido |
| `engine_backtest.py` | Ejecuta backtesting con estrategia dada |
| `engine_rt.py` | Streaming realtime de señales (reglas clásicas) |
| `engine_ai_rt.py` | Streaming realtime con modelo neural |
| `engine_train.py` | Entrenamiento de modelos Keras/TensorFlow |

### Flujo realtime (streaming)
- Java usa `RealtimeProcessSupervisor` / `StrategyRuntimeCoordinator`
- Python emite señales como líneas con prefijo `SIGNAL\t` por stdout
- Java parsea con `SignalProtocolParser.parseLineaLog()`
- Señales se entregan a `PaperTradingService.onSignal()`

---

## BaseEntity y soft delete

```java
@MappedSuperclass
public abstract class BaseEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Instant fechaCreacion;
    private boolean eliminado;  // soft delete — NUNCA DELETE físico
}
```

**Regla de soft delete**: usar `eliminado = true` en lugar de `DELETE`. Las queries siempre filtran `eliminado = false`. Nunca generar sentencias `DELETE` para entidades que extienden `BaseEntity`.

`Usuario` es la excepción: POJO puro sin JPA, con validaciones en constructor.

---

## Inyección de dependencias

```java
// ✅ PREFERIDO — Lombok @RequiredArgsConstructor
@Slf4j
@Service
@RequiredArgsConstructor
public class FetchService {
    private final VelaRepository velaRepository;
    private final JdbcTemplate jdbcTemplate;
    private final PythonBridgeFacade pythonBridgeFacade;
}

// ✅ ALTERNATIVA — constructor explícito con campos final
@Service
public class PaperTradingService {
    private final AccountingService accountingService;

    public PaperTradingService(AccountingService accountingService) {
        this.accountingService = accountingService;
    }
}

// ❌ PROHIBIDO
@Autowired
private FetchService fetchService;
```

---

## Persistencia masiva

```java
// ✅ Para volúmenes > 1000 registros — JdbcTemplate con ON DUPLICATE KEY UPDATE
jdbcTemplate.batchUpdate(sql, batch, BATCH_SIZE, (ps, item) -> {
    ps.setString(1, item.getSymbol());
    ps.setLong(2, item.getOpenTime());
    ps.setBigDecimal(3, new BigDecimal(item.getClose()));
    // ...
});

// ❌ PROHIBIDO para volúmenes grandes
velaRepository.saveAll(miles_de_velas);
```

Columnas de precio en MySQL: `DECIMAL(20,8)`, nunca `FLOAT` ni `DOUBLE`.
Timestamps en MySQL: `BIGINT UNSIGNED` (epoch millis), nunca `DATETIME`.

---

## CLI — CommandLineRunner con Scanner

La CLI usa `CommandLineRunner` + loop interactivo con `Scanner`. No es Spring Shell.

```java
// Patrón de comando CLI
public class BacktestCommand {
    private final CliCommandContext ctx;  // contiene servicios y scanner

    public void execute(String[] args) {
        CliInputValidator.requireLogin(ctx);
        String symbol = CommandParser.requireArg(args, "--symbol");
        // lógica delegada en application service
        ctx.getBacktestService().ejecutarBacktest(symbol, ...);
    }
}
```

Nunca lógica de negocio directa en comandos — siempre delegar a `application/`.

---

## Logging

```java
// ✅ SIEMPRE @Slf4j (Lombok)
@Slf4j
public class FetchService {
    public void fetchVelas(String symbol) {
        log.info("[fetch] Iniciando descarga de {} velas para {}", count, symbol);
        log.warn("[Python stderr] {}", stderrLine);
        log.error("[fetch] Error al conectar con Binance", ex);
    }
}

// ❌ PROHIBIDO
System.out.println("Descargando velas...");
```

---

## Excepciones

```java
// Jerarquía — siempre extender TradingServiceException
public class DataFetchException extends TradingServiceException { ... }
public class PythonProcessException extends TradingServiceException { ... }
public class ValidationException extends TradingServiceException { ... }

// ❌ PROHIBIDO — catch vacío o genérico sin re-throw
try { ... } catch (Exception e) {}

// ✅ CORRECTO
try { ... } catch (IOException e) {
    throw new DataFetchException("Error descargando velas para " + symbol, e);
}
```

---

## Testing

### Estructura de test por servicio
```java
@Slf4j
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FetchServiceTest {

    @Mock private VelaRepository velaRepository;
    @Mock private PythonBridgeFacade pythonBridgeFacade;
    @InjectMocks private FetchService fetchService;

    @Nested
    @DisplayName("CONSTRUCCIÓN: Instanciación del servicio")
    class ConstructionTests { ... }

    @Nested
    @DisplayName("fetchVelas() — Flujo de descarga")
    class FetchVelasTests {

        @Test
        @DisplayName("✓ Debe descargar y persistir velas cuando el símbolo es válido")
        void should_download_and_persist_velas_when_symbol_is_valid() {
            // Given
            var velas = crearVelasTestList(10);
            when(pythonBridgeFacade.execute(any())).thenReturn(velas);

            // When
            fetchService.fetchVelas("BTCUSDT", "1h");

            // Then
            verify(velaRepository, times(1)).saveAll(any());
        }
    }
}
```

### Convenciones de test
- `@Nested` para agrupar por funcionalidad (~8 grupos por clase)
- Naming: `should_do_something_when_condition()` en snake_case
- `@DisplayName` con emoji ✓ → `"✓ Debe ejecutar flujo completo"`
- Secciones Given/When/Then como comentarios
- Assertions: Hamcrest (`assertThat`, `is`, `notNullValue`, `hasSize`) + JUnit 5 (`assertThrows`)
- Mocking: Mockito (`when/thenReturn`, `verify`, `never()`, `times()`, `atLeastOnce()`)
- Helpers privados: `crearVelasTestList(int count)`, `crearSignalTestBUY()`, `crearInstanciaActivaTest()`
- **~35 tests por service class**: construcción, flujo feliz, errores, validaciones, puertos, boundary

### Tests de integración
```java
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
class VelaRepositoryIntegrationTest {
    @Autowired private VelaRepository velaRepository;
    @Autowired private TestEntityManager entityManager;
}
```

### Tests E2E
- Mockean la capa bridge pero validan el flujo Java completo
- `TradingFlowE2ETest`: fetch → indicators → backtest
- `StrategyLifecycleE2ETest`: crear → activar → señales → pausar → terminar

---

## Python — Convenciones

### Cabecera estándar de cualquier fichero
```python
from __future__ import annotations

import sys
import logging
import argparse
from dataclasses import dataclass, field
from pathlib import Path

import pandas as pd
import numpy as np

logger = logging.getLogger(__name__)
```

### Estrategias — tres métodos obligatorios
```python
class BaseStrategy(ABC):
    @abstractmethod
    def poblar_indicadores(self, df: pd.DataFrame, params: StrategyParams) -> pd.DataFrame: ...

    @abstractmethod
    def poblar_señales_entrada(self, df: pd.DataFrame, params: StrategyParams) -> pd.DataFrame: ...

    @abstractmethod
    def poblar_señales_salida(self, df: pd.DataFrame, params: StrategyParams) -> pd.DataFrame: ...
```

Reglas de estrategia:
- `poblar_señales_entrada()` añade columnas `enter_long` / `enter_short` (bool)
- `poblar_señales_salida()` añade columnas `exit_long` / `exit_short` (bool)
- Nunca lookahead bias: señal en índice `t` usa solo datos hasta `t`
- Nunca lógica de capital ni apertura de órdenes en la estrategia
- Señales se ejecutan en el OPEN de la vela `t+1`

### Prohibiciones Python
- `df.iterrows()` → usar `.itertuples(index=False)` o vectorización NumPy
- `print(...)` sin `flush=True` en engines que comunican con Java
- Errores a `sys.stderr`, nunca a `stdout`
- Variables sin type hints en funciones públicas
- API keys hardcodeadas → variables de entorno

---

## Reglas para generar código nuevo

1. Lógica de negocio en `application/`, entidades en `domain/`, adaptadores en `infrastructure/`, CLI en `interfaces/cli/`
2. Cada servicio nuevo necesita su test en el mismo paquete bajo `src/test/`
3. `BigDecimal` para cualquier dato financiero sin excepción
4. Constructor injection con campos `final` (preferir `@RequiredArgsConstructor`)
5. Excepciones tipadas extendiendo `TradingServiceException`
6. IPC con Python: siempre `PythonBridgeFacade` + `PythonBridgeRequest.builder()`, nunca `ProcessBuilder` directo
7. Batch inserts: `JdbcTemplate.batchUpdate()` para volúmenes > 1000
8. Tests de integración con `@SpringBootTest` + `@ActiveProfiles("test")` + H2
9. Soft delete: `eliminado = true`, nunca `DELETE` físico en entidades que extienden `BaseEntity`
10. Nombres de dominio en español, operaciones técnicas en inglés

---

## Prohibiciones globales

| Prohibido | Alternativa |
|---|---|
| `@Autowired` en campos | Constructor injection |
| `double`/`float` para precios o capital | `BigDecimal` |
| `System.out.println` | `@Slf4j` + `log.info/warn/error` |
| `saveAll()` para volúmenes grandes | `JdbcTemplate.batchUpdate()` |
| `ProcessBuilder` fuera de `bridge/` | `PythonBridgeFacade` + `PythonBridgeRequest.builder()` |
| Entidades JPA en CLI o respuestas | DTOs |
| `catch (Exception e) {}` vacío | Log + re-throw como excepción tipada |
| Credenciales hardcodeadas | `.env` + `java-dotenv` |
| JSON masivo Java↔Python | MessagePack con framing binario |
| `DELETE` físico en entidades con `BaseEntity` | `eliminado = true` (soft delete) |
| Comentarios que explican el qué | Nombres de método autoexplicativos |
| `df.iterrows()` en Python | `.itertuples()` o vectorización NumPy |
| `print(...)` sin `flush=True` en engines Python | `print(..., flush=True)` siempre |