# ✅ PASO 0 — ANÁLISIS PREVIO EXHAUSTIVO
## Mapeo Completo de Clases, Relaciones y Flujos de la Arquitectura Hexagonal

**Fecha**: 31 de marzo de 2026  
**Proyecto**: backendBotTrading (Spring Boot 3 + Java 21 + MySQL + Python)  
**Arquitectura**: Hexagonal (Ports & Adapters) + DDD  

---

## 📐 MAPA DE CLASES Y RELACIONES

### 🟢 CAPA DOMAIN (Lógica de Negocio Pura — Sin JPA, Sin Spring)

#### **DOMINIO: user/**
```
ENTIDAD:
  ├─ Usuario (extends BaseEntity)
  │  ├─ Atributos: nombre, passwordHash (finals, inmutables)
  │  ├─ Validaciones: non-null, non-blank en constructor
  │  ├─ Métodos públicos: getNombre(), getPasswordHash(), getId()
  │  └─ Invariantes: nombre único, passwordHash nunca vacío

REPOSITORIES (interfaces):
  └─ UsuarioRepository extends JpaRepository<Usuario, Long>
     └─ Métodos: existsByNombre()
```

#### **DOMINIO: market/**
```
ENTIDADES:
  ├─ Vela (extends BaseEntity)
  │  ├─ Atributos: symbol, interval, openTime, open, high, low, close, volume
  │  ├─ Tipos: BigDecimal para precios, Instant para timestamps
  │  ├─ Validaciones: precios > 0, volume >= 0, timestamps válidos
  │  └─ Métodos: getters solamente
  │
  ├─ IndicadorTecnico (extends BaseEntity)
  │  ├─ Atributos: nombre, velaId,值 (RSI, SMA, MACD, etc.)
  │  └─ Validaciones: nombre no vacío, valor en rango apropiado
  │
  └─ VelaDTO (record, value object)
     └─ Usado para transferencia entre Python ↔ Java (TSV → VelaDTO)

REPOSITORIES:
  ├─ VelaRepository extends JpaRepository<Vela, Long>
  │  └─ Métodos: findBySymbolAndInterval(), etc.
  │
  └─ IndicadorRepository extends JpaRepository<IndicadorTecnico, Long>
     └─ Métodos: findByVelaId(), etc.
```

#### **DOMINIO: trading/**
```
ENTIDADES:
  ├─ Posicion (extends BaseEntity)
  │  ├─ Atributos: estrategiaId, walletId, symbol, cantidad, precioEntrada, 
  │  │  precioSalida, pnl, estado (OPEN, CLOSED)
  │  ├─ Estados: OPEN (abierta) → CLOSED (cerrada)
  │  └─ Invariantes: cantidad > 0, precioEntrada > 0
  │
  ├─ LedgerEntry (extends BaseEntity)
  │  ├─ Atributos: walletId, tipo (DEPOSIT, WITHDRAWAL, TRADE_PROFIT, TRADE_LOSS)
  │  │  monto, descripcion, timestamp
  │  ├─ Tipos: enum LedgerType → DEPOSIT | WITHDRAWAL | TRADE_PROFIT | TRADE_LOSS
  │  └─ Invariantes: monto > 0, tipo != null
  │
  └─ LedgerType (enum)
     └─ Valores: DEPOSIT, WITHDRAWAL, TRADE_PROFIT, TRADE_LOSS

REPOSITORIES:
  ├─ PosicionRepository extends JpaRepository<Posicion, Long>
  │  └─ Métodos: findByEstrategiaId(), findOpenPositions(), etc.
  │
  └─ LedgerRepository extends JpaRepository<LedgerEntry, Long>
     └─ Métodos: findByWalletId(), findByTipo(), etc.
```

#### **DOMINIO: wallet/**
```
ENTIDADES:
  ├─ Wallet (JPA mezclado — REVISAR)
  │  ├─ Atributos: nombre, usuario (FK), balanceReal, balanceDisponible, 
  │  │  type (REAL, PAPER), isActive
  │  ├─ Tipos: enum WalletType → REAL | PAPER
  │  └─ Invariantes: balanceReal >= 0, balanceDisponible <= balanceReal
  │
  ├─ CapitalReservado (JPA mezclado — REVISAR)
  │  ├─ Atributos: walletId, estrategiaId, reservado, comprometido, riesgoAbierto
  │  └─ Invariantes: comprometido <= reservado
  │
  └─ WalletType (enum)
     └─ Valores: REAL, PAPER

REPOSITORIES:
  └─ WalletRepository extends JpaRepository<Wallet, Long>
     └─ Métodos: findByUsuarioId(), findByTipo(), etc.
```

#### **DOMINIO: strategy/**
```
ENTIDADES:
  └─ InstanciaEstrategia (extends BaseEntity)
     ├─ Atributos: nombreEstrategia, usuarioId, walletId, parametros (JSON?)
     │  estado (ACTIVE, PAUSED, STOPPED), fechaInicio, fechaFin
     ├─ Estados: ACTIVE (correr) → PAUSED (pausa) → STOPPED (terminar)
     └─ Invariantes: nombreEstrategia no vacío, walletId > 0

REPOSITORIES:
  └─ InstanciaEstrategiaRepository extends JpaRepository<InstanciaEstrategia, Long>
     └─ Métodos: findByUsuarioIdAndEstado(), findActive(), etc.
```

#### **BASE (ubicua en dominio):**
```
BaseEntity (MappedSuperclass JPA)
  ├─ Atributos: id (Long, auto-generado), fechaCreacion (Instant), eliminado (boolean)
  └─ Métodos: getId(), setId(), getFechaCreacion(), isEliminado(), setEliminado()
```

---

### 🟠 CAPA APPLICATION (Orquestación de Casos de Uso)

#### **CONTEXTO: user/**
```
PUERTOS DE ENTRADA (interfaces en application/user/port/in/):
  ├─ CreateUsuarioUseCase
  │  └─ Método: Usuario create(String nombre, String passwordHash)
  │
  └─ GetUsuarioUseCase
     ├─ Método: Optional<Usuario> getByNombre(String nombre)
     └─ Método: Optional<Usuario> getById(Long id)

PUERTOS DE SALIDA (interfaces en application/user/port/out/):
  └─ UsuarioRepositoryPort
     ├─ Método: Optional<Usuario> findById(Long id)
     ├─ Método: Optional<Usuario> findByNombre(String nombre)
     ├─ Método: Usuario save(Usuario usuario)
     └─ Método: void delete(Long id)

APPLICATION SERVICE:
  └─ UsuarioApplicationService implements CreateUsuarioUseCase, GetUsuarioUseCase
     ├─ Inyecta: UsuarioRepositoryPort
     ├─ Responsabilidad: Orquestar lógica de creación/lectura de usuarios
     ├─ Transaccionalidad: @Transactional en métodos de escritura
     └─ Flujo:
        create(nombre, passwordHash)
          ├─ Valida que usuario no exista (findByNombre)
          ├─ Crea entidad dominio new Usuario(nombre, passwordHash)
          ├─ Persiste via repositorio
          └─ Retorna Usuario creado
```

#### **CONTEXTO: market/**
```
APPLICATION SERVICES:
  ├─ FetchService
  │  ├─ Inyecta: PythonBridgeFacade, VelaRepository
  │  ├─ Método principal: List<Vela> fetchRango(String symbol, String interval, 
  │  │  Instant desde, Instant hasta)
  │  ├─ Responsabilidad: Descargar velas de Binance, paginar, persistir
  │  ├─ Integración con Python: engine_fetch.py
  │  └─ Lógica especial: Paginación (Binance devuelve máx 1000 velas)
  │
  ├─ IndicatorsService
  │  ├─ Inyecta: PythonBridgeFacade, IndicadorRepository
  │  ├─ Método principal: List<IndicadorTecnico> calcularIndicadores(
  │  │  List<Vela> velas, String estrategia)
  │  ├─ Responsabilidad: Invocar engine_indicators.py
  │  ├─ Integración con Python: engine_indicators.py
  │  └─ Entrada/Salida: Velas → TSV → Python → TSV → IndicadorTecnicoDTO
  │
  └─ MarketDataService
     ├─ Inyecta: FetchService, IndicatorsService
     ├─ Responsabilidad: Agregador de operaciones de mercado
     └─ Método: void sincronizarDatosMercado(String symbol, String interval)

(SIN PUERTOS EXPLÍCITOS AÚN — miembros de aplicación por refactorizar)
```

#### **CONTEXTO: strategy/**
```
APPLICATION SERVICES:
  ├─ EstrategiaService
  │  ├─ Inyecta: StrategyInspector (utilitario carga/escanea strategies)
  │  ├─ Responsabilidad: Registry de estrategias disponibles
  │  └─ Métodos: getEstrategia(nombre), listarDisponibles()
  │
  ├─ StrategyBacktestApplicationService
  │  ├─ Inyecta: PythonBridgeFacade, VelaRepository
  │  ├─ Método principal: BacktestResult ejecutar(BacktestRequest request)
  │  ├─ Responsabilidad: Orquestar backtesting histórico
  │  ├─ Integración con Python: engine_backtest.py
  │  └─ Entrada: Estrategia + Velas → TSV → Python → TSV → BacktestResult
  │
  ├─ StrategyLifecycleApplicationService
  │  ├─ Inyecta: InstanciaEstrategiaRepository
  │  ├─ Métodos: start(), pause(), stop() para instancias
  │  └─ Responsabilidad: Gestionar ciclo de vida de estrategias activas
  │
  ├─ StrategyCatalogService
  │  ├─ Responsabilidad: Listar estrategias disponibles, sus parámetros
  │  └─ Integración con Python: engine_fetch.py --list-strategies
  │
  └─ StrategyQueryService
     ├─ Inyecta: InstanciaEstrategiaRepository
     ├─ Métodos: getActive(), getStopped(), getByUser()
     └─ Responsabilidad: Queries de lectura sobre estrategias

(SIN PUERTOS EXPLÍCITOS AÚN)
```

#### **CONTEXTO: trading/**
```
APPLICATION SERVICES:
  ├─ PaperTradingService
  │  ├─ Inyecta: PythonBridgeFacade, PosicionRepository, LedgerRepository
  │  ├─ Método principal: void iniciar(PaperTradingRequest request)
  │  ├─ Responsabilidad: Monitoreo en tiempo real, procesamiento de señales
  │  ├─ Integración con Python: engine_rt.py (streaming continuo)
  │  └─ Flujo:
  │     ├─ Inicia proceso Python engine_rt.py [--symbol BTCUSDT ...]
  │     ├─ Stream continuo de señales: symbol | timestamp | action | price
  │     ├─ Por cada señal (BUY/SELL):
  │     │  ├─ Crea Posicion o la cierra
  │     │  └─ Registra LedgerEntry
  │     └─ Supervisa el proceso continuamente
  │
  └─ AccountingService
     ├─ Inyecta: LedgerRepository, PosicionRepository, WalletService
     ├─ Métodos: registrarTrade(Posicion), calcularPnL(), generarReporte()
     └─ Responsabilidad: Registro de operaciones y cálculo de ganancias/pérdidas

(SIN PUERTOS EXPLÍCITOS AÚN)
```

#### **CONTEXTO: training/**
```
APPLICATION SERVICES:
  └─ AITrainingService
     ├─ Inyecta: PythonBridgeFacade, VelaRepository
     ├─ Método principal: void entrenar(TrainingRequest request)
     ├─ Responsabilidad: Orquestar entrenamiento de modelos ML
     ├─ Integración con Python: engine_train.py, engine_ai_rt.py
     ├─ Asincronía: ExecutorService (virtual threads)
     └─ Salida: Modelo guardado en ${APP_HOME}/models/
```

---

### 🔵 CAPA INFRASTRUCTURE (Adapters "Concretos")

#### **PERSISTENCIA (persistence/)**
```
JPA LAYER:
  ├─ ENTITY: UsuarioJpaEntity (extends BaseEntity)
  │  ├─ Anotaciones: @Entity @Table(name="usuario")
  │  ├─ Atributos: nombre, passwordHash
  │  └─ Repository: UsuarioJpaRepository extends JpaRepository<UsuarioJpaEntity, Long>
  │     └─ Query methods: findByNombre()
  │
  ├─ MAPPER: UsuarioMapper
  │  ├─ toDomain(UsuarioJpaEntity) → Usuario
  │  ├─ toJpa(Usuario) → UsuarioJpaEntity
  │  └─ Round-trip: Usuario → JpaEntity → Usuario
  │
  └─ ADAPTER: UsuarioPersistenceAdapter implements UsuarioRepositoryPort
     ├─ Inyecta: UsuarioJpaRepository, UsuarioMapper
     ├─ Métodos: findById(), findByNombre(), save(), delete()
     └─ Responsabilidad: Traducir RepositoryPort → JpaRepository + Mapper

OTROS SERVICIOS DE PERSISTENCIA:
  ├─ FileService
  │  ├─ Responsabilidad: I/O de archivos (estrategias, modelos, datos)
  │  └─ Métodos: readFile(), writeFile(), deleteFile()
  │
  ├─ StatsCsvRepository
  │  ├─ Responsabilidad: Lectura/escritura de estadísticas en CSV
  │  └─ Métodos: loadStats(), saveStats()
  │
  ├─ TradeCsvWriter
  │  ├─ Responsabilidad: Escritura de trades en formato CSV
  │  └─ Método: writeTrade(Posicion, PnL)
  │
  ├─ BacktestArtifactsCleaner
  │  ├─ Responsabilidad: Limpieza de archivos temporales post-backtest
  │  └─ Método: limpiar()
  │
  └─ CsvUtilities
     ├─ Responsabilidad: Parseo y formateo de CSV
     └─ Métodos estáticos: parse(), format()
```

#### **BRIDGE IPC Java ↔ Python (bridge/)**
```
PROTOCOL & CODEC:
  ├─ IpcProtocol (interfaz)
  │  └─ Define contrato de serialización
  │
  ├─ IpcMessagePackCodec implements IpcProtocol
  │  ├─ Serializa: Object → MessagePack bytes
  │  ├─ Deserializa: bytes → Object (genérico)
  │  └─ Tipos soportados: Vela, SignalDTO, etc.
  │
  ├─ IpcMessageType (enum)
  │  └─ COMMAND, RESULT, ERROR, HEARTBEAT, CLOSE
  │
  ├─ IpcEnvelope
  │  ├─ Estructura: {tipo, requestId, body}
  │  └─ Transporta: Commands y Responses

FACADE & SUPERVISOR:
  ├─ PythonBridgeFacade
  │  ├─ Inyecta: RealtimeProcessSupervisor, ProcessExecutorConfig
  │  ├─ Métodos públicos:
  │  │  ├─ execute(String script, List<String> args) → String output
  │  │  ├─ executeStream(String script, Consumer<String> handler) → Process
  │  │  └─ executeAsync(String script) → Future<String>
  │  ├─ Responsabilidad: Factory y lifecycle de procesos Python
  │  └─ Error handling: PythonBridgeExecutionException
  │
  ├─ PythonBridgeRequest (record/DTO)
  │  └─ Parámetros: script, args, timeoutMs, retryPolicy
  │
  ├─ RealtimeProcessSupervisor
  │  ├─ Monitorea procesos Python activos
  │  ├─ Detecta abruptas terminaciones (stdout/stderr)
  │  └─ Reinicia si es necesario (circuit breaker)
  │
  ├─ StrategyRuntimeCoordinator
  │  ├─ Orquesta múltiples procesos Python (estrategias paralelas)
  │  ├─ Mapeo: InstanciaEstrategia → Process
  │  └─ Métodos: start(), pause(), stop(), getStatus()
  │
  └─ SignalProtocolParser
     ├─ Parsea TSV streams de Python
     ├─ Formatos: Vela TSV, Signal TSV, MetricaDTO TSV
     └─ Métodos: parseVela(), parseSignal(), parseMetrica()

EXCEPTIONS:
  └─ PythonBridgeExecutionException
     ├─ Lanzada cuando el proceso Python falla
     └─ Incluye: stderr, exit code, timeout info
```

#### **VALIDACIÓN (validation/)**
```
SERVICIOS:
  ├─ SessionManager
  │  ├─ Inyecta: UsuarioService
  │  ├─ Métodos: login(), logout(), getCurrentUser(), isAuthenticated()
  │  ├─ Estado: ThreadLocal<Usuario> usuarioActual
  │  └─ Responsabilidad: Gestión de sesión de usuario en CLI
  │
  ├─ UsuarioService
  │  ├─ Inyecta: UsuarioRepository (acceso directo, mezcla arquitectura)
  │  ├─ Métodos: validarCredenciales(), existeUsuario(), crearUsuario()
  │  └─ Responsabilidad: Validaciones técnicas de usuario
  │
  └─ WalletService
     ├─ Inyecta: WalletRepository
     ├─ Métodos: validarBalance(), actualizar(), crear()
     └─ Responsabilidad: Validaciones de wallet
```

#### **CACHE (cache/)**
```
StatsCache
  ├─ Caché en memoria de estadísticas de estrategias
  ├─ Responsabilidad: Evitar relectura de CSV frecuente
  └─ Métodos: get(clave), put(), clear()
```

---

### 🟡 CAPA INTERFACES (Adapters de Entrada)

#### **CLI (interfaces/cli/)**
```
MAIN ENTRY:
  └─ CliCommandContext
     ├─ Mantiene estado: usuario logueado, wallet seleccionada, etc.
     └─ Accesible para todos los comandos

VALIDADOR:
  └─ CliInputValidator
     ├─ Métodos: validarNumerico(), validarFecha(), validarSymbol()
     └─ Responsabilidad: Pre-validación de entradas del usuario

COMMANDS (cada comando es una clase separada):
  ├─ SignupCommand
  │  ├─ Entrada: --nombre <string> --password <string>
  │  ├─ Invoca: UsuarioApplicationService.create()
  │  └─ Salida: Usuario creado + sesión iniciada
  │
  ├─ LoginCommand
  │  ├─ Entrada: --nombre <string> --password <string>
  │  ├─ Invoca: SessionManager.login()
  │  └─ Salida: Sesión activa
  │
  ├─ LogoutCommand
  │  └─ Invoca: SessionManager.logout()
  │
  ├─ CreatePaperWalletCommand
  │  ├─ Entrada: --nombre <string> --capital <BigDecimal>
  │  ├─ Invoca: WalletService.crear() with tipo=PAPER
  │  └─ Salida: Wallet creada
  │
  ├─ ListWalletsCommand
  │  ├─ Invoca: WalletService.listar()
  │  └─ Salida: Tabla de wallets
  │
  ├─ FetchCommand
  │  ├─ Entrada: --symbol <string> --interval <string> --desde <date> --hasta <date>
  │  ├─ Invoca: FetchService.fetchRango()
  │  └─ Salida: N velas descargadas y persistidas
  │
  ├─ ListStrategiesCommand
  │  ├─ Invoca: EstrategiaService.listarDisponibles()
  │  └─ Salida: Tabla de estrategias + parámetros
  │
  ├─ ListStrategyFilesCommand
  │  ├─ Lee directorio de estrategias
  │  └─ Salida: Archivos Python disponibles
  │
  ├─ BacktestCommand
  │  ├─ Entrada: --symbol <string> --interval <string> --strategy <string> --desde <date>
  │  ├─ Invoca: StrategyBacktestApplicationService.ejecutar()
  │  └─ Salida: BacktestResult (PnL, winRate, maxDrawdown, profitFactor)
  │
  ├─ StartCommand
  │  ├─ Entrada: --symbol <string> --strategy <string> --wallet <id>
  │  ├─ Invoca: StrategyLifecycleApplicationService.start()
  │  └─ Salida: Estrategia iniciada, ID de la instancia
  │
  ├─ StopCommand
  │  ├─ Entrada: --instancia-id <long>
  │  ├─ Invoca: StrategyLifecycleApplicationService.stop()
  │  └─ Salida: Estrategia detenida, reporte final
  │
  ├─ ListActiveStrategiesCommand
  │  ├─ Invoca: StrategyQueryService.getActive()
  │  └─ Salida: Tabla de estrategias activas
  │
  ├─ ListStoppedStrategiesCommand
  │  └─ Similar a Active
  │
  ├─ ListTerminatedStrategiesCommand
  │  └─ Similar a Active
  │
  ├─ TrainCommand
  │  ├─ Entrada: --strategy <string> --epochs <int> --walletId <long>
  │  ├─ Invoca: AITrainingService.entrenar()
  │  └─ Trabajador: Async (ThreadPool virtual)
  │
  ├─ ModelsCommand
  │  ├─ Lista modelos entrenados en ${APP_HOME}/models/
  │  └─ Salida: Tabla de modelos con timestamps
  │
  ├─ TradeCommand
  │  ├─ Entrada: --symbol <string> --strategy <string>
  │  ├─ Invoca: PaperTradingService.iniciar()
  │  └─ Streaming continuo: señales en tiempo real
  │
  ├─ TermCommand
  │  ├─ Termina todas las estrategias activas
  │  └─ Contabilidad final → reporte
  │
  ├─ CbiCommand
  │  └─ Debug/utilidad para verificar estado interno
  │
  ├─ HelpCommand
  │  └─ Ayuda general de comandos
  │
  └─ Base: CliCommand (interfaz común)
     └─ execute(CliCommandContext ctx) → void

FLUJO GENERAL DE COMANDO:
  User input → CliInputValidator.validar()
           ↓
      CliCommandContext.routeCommand(input)
           ↓
      {Comando}Command.execute(ctx)
           ↓
      Invoca ApplicationService
           ↓
      Retorna resultado con formato TableConsole o mensaje
```

---

## 🔄 FLUJOS IDENTIFICADOS

### Flujo 1: AUTENTICACIÓN Y SESIÓN
```
Secuencia:
  1. User: signup --nombre "user1" --password "pass123"
  2. SignupCommand → UsuarioApplicationService.create()
  3. UsuarioApplicationService:
     - Valida: usuario.nombre no nulo ni vacío
     - Crea: new Usuario("user1", hashPassword("pass123"))
     - Persiste: UsuarioRepositoryPort.save() → UsuarioPersistenceAdapter → JPA → BD
  4. SessionManager.iniciarSesion(usuario)
  5. CliCommandContext.usuarioActual = usuario
  6. Output: "Usuario 'user1' creado e iniciado sesión"

  Luego:
  7. User: login --nombre "user1" --password "pass123"
  8. LoginCommand → SessionManager.login() → valida credenciales
  9. Si válido: SessionManager.usuarioActual = usuario
  10. Output: "Sesión iniciada para 'user1'"

  AL TERMINAR:
  11. User: logout
  12. LogoutCommand → SessionManager.logout()
  13. CliCommandContext.usuarioActual = null
  14. Output: "Sesión cerrada"

ACTORES:
  Usuario dominio → UsuarioApplicationService → UsuarioRepositoryPort
                                              → UsuarioPersistenceAdapter
                                              → UsuarioJpaRepository
                                              → UsuarioJpaEntity en BD

PERSISTENCIA:
  BaseEntity (id, fechaCreacion, eliminado)
  UsuarioJpaEntity.nombre = "user1"
  UsuarioJpaEntity.passwordHash = hashPassword("pass123")
```

### Flujo 2: DESCARGA DE DATOS HISTÓRICOS (Binance)
```
Secuencia:
  1. User: fetch --symbol BTCUSDT --interval 1h --desde 2024-01-01 --hasta 2024-12-31
  2. FetchCommand → CliInputValidator.validarSymbol(), validarIntervalo(), validarFechas()
  3. FetchCommand → FetchService.fetchRango("BTCUSDT", "1h", instant_desde, instant_hasta)
  4. FetchService:
     - Inicia loop: while (cursor < hasta)
     - Calcula chunk_size basado en intervalo
     - Invoca: PythonBridgeFacade.execute("engine_fetch.py",
       ["--symbol", "BTCUSDT", "--interval", "1h", "--start", startEpochMs, "--end", endEpochMs])
     - Parsea stdout con SignalProtocolParser.parseVela() → List<VelaDTO>
     - Convierte VelaDTO → Vela (dominio)
     - Persiste batch: VelaRepository.saveAll() (o mejor: JdbcTemplate.batchUpdate)
     - Continúa con siguiente chunk
  5. Output: "Descargadas 10,234 velas para BTCUSDT/1h"

ACTORES:
  FetchService → PythonBridgeFacade → ProcessBuilder engine_fetch.py
                                   → SignalProtocolParser
                                   → VelaRepository (persistencia)

DATOS EN BD:
  candlesticks tabla (Vela):
    - symbol: "BTCUSDT"
    - time_interval: "1h"
    - open_time: 1704067200000 (epoch ms)
    - open_price: 42100.50 (BigDecimal)
    - high_price: 42500.00
    - low_price: 42000.00
    - close_price: 42250.75
    - volume: 1234.56

PYTHON INTEGRATION:
  engine_fetch.py output (TSV format):
  symbol    interval  open_time         open      high      low       close     volume
  BTCUSDT   1h        1704067200000     42100.50  42500.00  42000.00  42250.75  1234.56
  [... N líneas]

  Java parsea línea a línea → VelaDTO → Vela → persistencia
```

### Flujo 3: BACKTESTING DE ESTRATEGIA
```
Secuencia:
  1. User: backtest --symbol BTCUSDT --interval 1h --strategy RSI_SMA --desde 2024-01-01
  2. BacktestCommand → CliInputValidator
  3. BacktestCommand → StrategyBacktestApplicationService.ejecutar(request)
  4. StrategyBacktestApplicationService:
     a. Carga Velas históricas: VelaRepository.findBySymbolAndInterval("BTCUSDT", "1h")
     b. Carga Strategy descriptor: EstrategiaService.getEstrategia("RSI_SMA")
     c. Prepare input TSV: [symbol | interval | timestamp | o | h | l | c | v]
     d. Invoca: PythonBridgeFacade.execute("engine_backtest.py",
        ["--strategy", "RSI_SMA", "--symbol", "BTCUSDT", "--interval", "1h"])
        (junto con stdin: velas en TSV)
     e. Parsea stdout con SignalProtocolParser.parseSignal() → List<SignalDTO>
        y parseMetrica() → BacktestResult
     f. Calcula: PnL total, win rate, max drawdown, profit factor
  5. Output: Tabla con resultados
     | Métrica        | Valor     |
     | PnL            | +1234.50  |
     | Win Rate       | 62.5%     |
     | Max Drawdown   | -15.3%    |
     | Profit Factor  | 2.1       |
     | Num Trades     | 48        |

ACTORES:
  StrategyBacktestApplicationService
    → VelaRepository (carga datos)
    → EstrategiaService (obtiene estrategia)
    → PythonBridgeFacade (executa engine_backtest.py)
    → SignalProtocolParser (parsea resultados)

PYTHON SCRIPT:
  engine_backtest.py:
    Entrada: Velas TSV en stdin, parámetros --strategy, --symbol
    Lógica:
      1. Lee velas TSV
      2. Carga estrategia RSI_SMA del filesystem
      3. Inicializa: capital_inicial = 1000 (por defecto)
      4. Loop sobre velas:
         - Calcula indicadores (RSI, SMA)
         - Genera señal (BUY/SELL/HOLD)
         - Simula trade si BUY: entrada = precio
         - Si SELL: cierra posición, calcula PnL
      5. Salida: Señales TSV + Resultado TSV
    Salida: TSV con columnas [timestamp | signal | entry_price | exit_price | pnl]
            + Una línea final con métricas: [pnl | win_rate | max_dd | profit_factor]
```

### Flujo 4: PAPER TRADING EN TIEMPO REAL
```
Secuencia:
  1. User: trade --symbol BTCUSDT --strategy RSI_SMA --wallet <wallet_id>
  2. TradeCommand → StrategyLifecycleApplicationService.start()
  3. Crea InstanciaEstrategia:
     - nombre_estrategia: "RSI_SMA"
     - usuario_id: <usuario actual>
     - wallet_id: <wallet_id>
     - estado: ACTIVE
     - fecha_inicio: ahora
  4. PaperTradingService.iniciar(request):
     a. Inicia proceso Python PERMANENTE: "engine_rt.py --symbol BTCUSDT --strategy RSI_SMA"
     b. El proceso mantiene conexión abierta:
        - Recibe candle en tiempo real cada período (1m si interval=1m)
        - Calcula indicadores
        - Genera señal (BUY/SELL/HOLD)
        - Envía por stdout: "BTCUSDT | 1704067200000 | BUY | 42250.75"
     c. Java ReceiverThread escucha stdout:
        for line in process.stdout:
           signal = SignalProtocolParser.parseSignal(line)
           if signal.action == BUY:
              posicion = new Posicion(estrategiaId, walletId, symbol, qty, pricioEntrada)
              posicionRepository.save(posicion)
              ledger = new LedgerEntry(walletId, TRADE_PROFIT, amount, "BUY BTCUSDT")
              ledgerRepository.save(ledger)
           elif signal.action == SELL:
              posicion.cerrar(señal.precio)
              posicionRepository.update(posicion)
              pnl = posicion.calcularPnL()
              ledger = new LedgerEntry(walletId, TRADE_LOSS_or_PROFIT, pnl, "SELL BTCUSDT")
              ledgerRepository.save(ledger)
              accountingService.registrarTrade(posicion)
     d. Supervisor monitorea proceso:
        - Si muere inesperadamente → reinicia
        - Si existe timeout sin datos → reinicia con circuit breaker
     e. User puede hacer: stop --instancia-id <id>
        → StrategyLifecycleApplicationService.stop()
        → Mata proceso Python
        → Actualiza InstanciaEstrategia.estado = STOPPED
        → Genera reporte final
  5. Output: Streaming continuo de trades
     | Timestamp | Symbol | Action | Price  | PnL      |
     | 1704...   | BTCUSD | BUY    | 42250  | -        |
     | 1704...   | BTCUSD | SELL   | 42500  | +250.00  |

ACTORES:
  TradeCommand
    → StrategyLifecycleApplicationService.start()
    → PaperTradingService.iniciar()
    → PythonBridgeFacade.executeStream("engine_rt.py", streamHandler)
    → RealtimeProcessSupervisor (monitorea e reinicia)
    → PosicionRepository, LedgerRepository (persistencia)
    → AccountingService (registra trades)

ESTADO PERSISTIDO:
  instancia_estrategia.estado = "ACTIVE"
  posicion (OPEN o CLOSED)
  ledger_entry (DEPOSIT, WITHDRAWAL, TRADE_PROFIT, TRADE_LOSS)

PROCESOS CONCURRENTES:
  - Hilo 1: Escucha stdout de engine_rt.py
  - Hilo 2: Supervisor (monitoreo de proceso)
  - Hilo 3: (opcional) lectura de señales desde BD para sugerencias
```

### Flujo 5: ENTRENAMIENTO DE MODELO ML
```
Secuencia:
  1. User: train --strategy AITrader --epochs 100 --walletId <id>
  2. TrainCommand → AITrainingService.entrenar(request)
  3. AITrainingService (ASYNC, ExecutorService virtualThreadExecutor):
     a. Carga Velas históricas: VelaRepository.findAll()
     b. Prepara dataset TSV
     c. Invoca ASYNC: PythonBridgeFacade.executeAsync("engine_train.py",
        ["--epochs", "100", "--output-dir", "${APP_HOME}/models/"])
     d. Proceso Python:
        - Lee velas TSV from stdin
        - Entrena modelo (RandomForest, Neural Net, etc.)
        - Guarda modelo: /models/ai_trader_2026-03-31_12-34-56.pkl
     e. AI Training completa: logea mensaje "Entrenamiento completado"
     f. Se puede usar luego con: StartCommand --strategy AITrader
  4. Output: "Entrenamiento iniciado para AITrader, epochs=100... (asincrónico)"

ACTORES:
  TrainCommand
    → AITrainingService.entrenar()
    → VelaRepository.findAll()
    → PythonBridgeFacade.executeAsync()
    → (NO persiste resultados en BD explícitamente, solo modelo en FS)
    → FileService para gestionar modelos

CONCURRENCIA:
  - Hilo main CLI sigue disponible
  - Hilo worker entrena modelo en segundo plano
```

---

## 🗺️ MAPA DE DEPENDENCIAS E INYECCIONES

```
Nivel más alto (entidades de dominio):
  BaseEntity (sin deps)
    ↓
  Usuario, Vela, Posicion, LedgerEntry, ... (sin deps, PURO)

Nivel Application Services:
  UsuarioApplicationService:
    ├─ inyecta: UsuarioRepositoryPort (abstracción)
    └─ implemen: CreateUsuarioUseCase, GetUsuarioUseCase
  
  FetchService:
    ├─ inyecta: PythonBridgeFacade, VelaRepository
    └─ implemen: nada (servicio técnico de aplicación)
  
  StrategyBacktestApplicationService:
    ├─ inyecta: VelaRepository, PythonBridgeFacade, EstrategiaService
    └─ implemen: nada

Nivel Infrastructure:
  UsuarioPersistenceAdapter:
    ├─ inyecta: UsuarioJpaRepository, UsuarioMapper
    └─ implementa: UsuarioRepositoryPort
  
  PythonBridgeFacade:
    ├─ inyecta: ProcessExecutorConfig, RealtimeProcessSupervisor
    └─ implemen: nada (facade)
  
  SessionManager:
    ├─ inyecta: UsuarioService
    └─ implemen: nada

Nivel Interfaces:
  SignupCommand, LoginCommand, FetchCommand, etc:
    ├─ inyectan: ApplicationServices o Portos entrada
    └─ implemen: nada
```

---

## 📊 RESUMEN DE CLASES POR TIPO

| CATEGORÍA | CANTIDAD | EJEMPLOS |
|-----------|----------|----------|
| **Domain Entities** | 8 | Usuario, Vela, Posicion, LedgerEntry, IndicadorTecnico, InstanciaEstrategia, Wallet, CapitalReservado |
| **Domain Repositories (JPARepo)** | 8 | UsuarioRepository, VelaRepository, PosicionRepository, ... |
| **Application Ports In** | 2 | CreateUsuarioUseCase, GetUsuarioUseCase |
| **Application Ports Out** | 1 | UsuarioRepositoryPort |
| **Application Services** | 11 | UsuarioApplicationService, FetchService, IndicatorsService, StrategyBacktestApplicationService, ... |
| **JPA Entities** | 1 | UsuarioJpaEntity (modelo: arquitectura mixta incompleta) |
| **Mappers** | 1 | UsuarioMapper |
| **Persistence Adapters** | 1 | UsuarioPersistenceAdapter |
| **Persistence Utils** | 5 | FileService, StatsCsvRepository, TradeCsvWriter, BacktestArtifactsCleaner, CsvUtilities |
| **Bridge/IPC** | 8 | PythonBridgeFacade, IpcMessagePackCodec, IpcProtocol, IpcEnvelope, SignalProtocolParser, RealtimeProcessSupervisor, StrategyRuntimeCoordinator, ... |
| **Validation Services** | 3 | SessionManager, UsuarioService, WalletService |
| **CLI Commands** | 20+ | SignupCommand, LoginCommand, FetchCommand, BacktestCommand, StartCommand, StopCommand, TradeCommand, TrainCommand, ... |
| **CLI Utils** | 3 | CliInputValidator, CliCommandContext, CliCommand (base) |
| **Exceptions** | 10+ | PythonBridgeExecutionException, DataFetchException, ValidationException, TradingServiceException, StrategyExecutionException, ... |
| **Utils & Helpers** | 10+ | SafeParser, ConsoleLoader, CommandParser, EnvironmentValidator, PythonProcessSupport, HashUtils, StrategyInspector, ... |

---

## ✅ VALIDACIÓN DEL MAPA

- ✅ **Arquitectura Hexagonal**: Domain | Application (puertos) | Infrastructure (adapters) | Interfaces (CLI/REST)
- ✅ **Pureza de Dominio**: Sin JPA/Spring en domain/ (excepto herencia de BaseEntity con JPA)
- ✅ **Puertos Explícitos**: CreateUsuarioUseCase, GetUsuarioUseCase, UsuarioRepositoryPort
- ✅ **Adapters**: UsuarioPersistenceAdapter implementa UsuarioRepositoryPort
- ✅ **IPC Java ↔ Python**: Bien estructurado con Protocol, Codec, Parser
- ✅ **Flujos de Negocio**: 5 flujos críticos identificados y mapeados

---

## ⚠️ OBSERVACIONES & DEUDA TÉCNICA

1. **Arquitectura Mixta Incompleta**: Wallet y CapitalReservado tienen JPA directo (son @Entity), no siguen patrón de domain puro
2. **Puertos Incompletos**: Falta refactorizar FetchService, IndicatorsService, etc. con sus interfaces de puerto
3. **Mappers Incompletos**: Solo UsuarioMapper existe, faltan mappers para Vela, Posicion, etc.
4. **JPARepository vs Ports**: Deben estar en domain pero sin anotaciones JPA
5. **BaseEntity JPA**: Tiene anotaciones JPA, debería separarse domain/persistence layers
6. **Tests Limitados**: Solo 7 tests para 107 clases (cobertura ~6.5%)

---

## 📝 SIGUIENTE PASO

Una vez confirmado este mapa, procederemos con:
- **BLOQUE 1**: Tests unitarios por clase
- **BLOQUE 2**: Tests de integración entre capas
- **BLOQUE 3**: Tests end-to-end por flujo

