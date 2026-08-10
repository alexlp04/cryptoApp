# CryptoApp

<p align="center">
	<strong>Plataforma experimental de trading algorítmico sobre criptomonedas</strong><br>
	Java 21 + Spring Boot 3.3.5 para la orquestación, Python 3.11+ para cálculo, ML y backtesting,
	con arquitectura hexagonal e IPC binario MessagePack.
</p>

<p align="center">
	<img alt="Java 21" src="https://img.shields.io/badge/Java-21-E76F00?style=for-the-badge&logo=openjdk&logoColor=white">
	<img alt="Spring Boot 3.3.5" src="https://img.shields.io/badge/Spring%20Boot-3.3.5-6DB33F?style=for-the-badge&logo=springboot&logoColor=white">
	<img alt="Python 3.11+" src="https://img.shields.io/badge/Python-3.11%2B-3776AB?style=for-the-badge&logo=python&logoColor=white">
	<img alt="MySQL" src="https://img.shields.io/badge/MySQL-8%2B-4479A1?style=for-the-badge&logo=mysql&logoColor=white">
	<img alt="MessagePack IPC" src="https://img.shields.io/badge/IPC-MessagePack-005F73?style=for-the-badge">
	<img alt="TFG" src="https://img.shields.io/badge/Universidad%20de%20Murcia-TFG-7A0019?style=for-the-badge">
</p>

<p align="center">
	<a href="https://github.com/alexlp04/cryptoapp/actions/workflows/ci.yml">
		<img alt="CI" src="https://github.com/alexlp04/cryptoapp/actions/workflows/ci.yml/badge.svg">
	</a>
</p>

> CryptoApp no nace como un simple bot de señales, sino como un banco de pruebas reproducible para estudiar estrategias, validar hipótesis, comparar modelos predictivos y simular operativa sin dinero real. El proyecto forma parte del Trabajo de Fin de Grado de Alejandro Lopez Lopez en el Grado en Ingenieria Informatica de la Universidad de Murcia.

## Vision General

CryptoApp integra en un mismo flujo:

- descarga incremental de velas OHLCV desde Binance,
- calculo de indicadores tecnicos,
- backtesting clasico y asistido por IA,
- entrenamiento y optimizacion de modelos supervisados,
- paper trading en tiempo real,
- persistencia y contabilidad sobre carteras virtuales,
- generacion de resultados reproducibles en CSV y artefactos de modelos.

La aplicacion se ejecuta como una CLI interactiva, no como una API REST. Java coordina la sesion, la persistencia, la contabilidad y el ciclo de vida de las estrategias; Python actua como motor de calculo para fetch, indicadores, simulacion, inferencia, entrenamiento y optimizacion.

## Por Que Es Interesante

- Combina backend empresarial y analitica cuantitativa dentro de una sola base de codigo.
- Separa responsabilidades mediante arquitectura hexagonal, evitando mezclar dominio con detalles de infraestructura.
- Usa un puente Java-Python con serializacion MessagePack y framing de 4 bytes big-endian, en lugar de una integracion ad hoc.
- Permite comparar el baseline clasico con modelos de ML dentro del mismo entorno experimental.
- Nace de un TFG real y conserva un enfoque claramente academico y reproducible.

## Arquitectura

```mermaid
flowchart LR
		CLI[CLI interactiva<br/>Spring Boot] --> APP[Servicios de aplicacion]
		APP --> DOM[Dominio y reglas de negocio]
		APP --> INF[Infraestructura Java]
		INF --> DB[(MySQL / H2 en tests)]
		INF --> IPC[IPC MessagePack<br/>4-byte big-endian]
		IPC --> PY[Engines Python]
		PY --> BIN[Binance API / WebSocket]
		PY --> MOD[Modelos ML y backtests]
```

### Reparto de responsabilidades

| Capa | Tecnologia | Responsabilidad principal |
| --- | --- | --- |
| Orquestacion | Java 21 + Spring Boot | CLI, sesion, persistencia, contabilidad, coordinacion de procesos |
| Dominio | Java | wallets, posiciones, ledger, estrategias y casos de uso |
| Analitica | Python 3.11+ | fetch, indicadores, entrenamiento, optimizacion, inference |
| Persistencia | MySQL / H2 | velas, resultados, sesiones y pruebas |
| Comunicacion | MessagePack | contrato estable entre Java y Python |

## Capacidades Principales

| Area | Que hace |
| --- | --- |
| Gestion de usuarios | registro, login y sesion interactiva |
| Carteras virtuales | crea paper wallets y gestiona capital disponible y comprometido |
| Datos de mercado | descarga historicos de Binance y rellena huecos incrementalmente |
| Backtesting | ejecuta estrategias clasicas o guiadas por modelo |
| IA aplicada | entrena, evalua y optimiza modelos como LightGBM, XGBoost, randomForest, SVM o redes neuronales |
| Trading en tiempo real | lanza sesiones de paper trading con senales clasicas o predicciones del modelo |
| Resultados | persiste metricas, CSVs, modelos y logs para analisis posterior |

## Mapa Del Repositorio

```text
cryptoapp/
├── code/
│   ├── backendBotTrading/
│   │   └── src/main/
│   │       ├── java/com/bottrading/
│   │       │   ├── {market,trading,training,strategy,wallet,user,backtesting}/
│   │       │   │       # un paquete por dominio, cada uno con application/domain/infrastructure
│   │       │   ├── interfaces/cli/commands/   # comandos de la CLI (implementacion propia)
│   │       │   └── config/  shared/
│   │       └── resources/db/migration/        # migraciones Flyway (fuente del esquema)
│   ├── scripts/             # engines Python: fetch, indicators, backtest, train, optimize, rt
│   │   └── tests/           # suite pytest de los engines
│   └── strategies/          # estrategias Python cargadas dinamicamente
├── .github/workflows/       # CI: Java, Python y validacion de esquema contra MySQL
├── info/                    # apoyo tecnico (referencia historica, ver nota mas abajo)
├── models/                  # artefactos de modelos entrenados
├── results/                 # CSVs de backtesting y optimizacion
├── logs/                    # logs de ejecucion
├── ruff.toml  mypy.ini      # configuracion de calidad Python (fijada en el repo)
└── requirements.txt         # dependencias Python del proyecto
```

Cada dominio sigue arquitectura hexagonal: `domain` no depende de `infrastructure`.

## Requisitos

- Java 21
- Maven 3.9+
- Python 3.11 o superior
- MySQL 8+ para ejecucion normal
- H2 para tests Java
- Entorno virtual Python con las dependencias de `requirements.txt`

## Puesta En Marcha

### 1. Clonar el repositorio

```bash
git clone https://github.com/alexlp04/cryptoapp.git
cd cryptoapp
```

### 2. Crear y preparar el entorno Python

```bash
python3.11 -m venv .venv
source .venv/bin/activate
pip install --upgrade pip
pip install -r requirements.txt
```

### 3. Interprete Python

El backend resuelve el interprete segun el sistema operativo, sin necesidad de enlaces
simbolicos (`AppConstants.resolveDefaultPythonExecutable`):

| Sistema | Ruta relativa a la raiz del proyecto |
| --- | --- |
| Windows | `.venv/Scripts/python.exe` |
| Linux y macOS | `.venv/bin/python3` |

Para apuntar a otro interprete, define `CRYPTOAPP_PYTHON`; tiene prioridad sobre ambas rutas.

### 4. Configurar la base de datos

Crea el archivo `code/backendBotTrading/.env` con las variables minimas requeridas:

```env
DB_URL=jdbc:mysql://localhost:3306/bottradingdb?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC
DB_USER=tu_usuario
DB_PASSWORD=tu_password
```

**El esquema lo crea y versiona Flyway**, no Hibernate: `ddl-auto` esta en `validate`, asi que
basta con una base vacia. Las migraciones viven en
`code/backendBotTrading/src/main/resources/db/migration/`. Al anadir o cambiar un campo de una
entidad hay que crear una migracion `V<n>__descripcion.sql`; `SchemaMigrationValidationTest`
falla si entidades y migraciones divergen.

### 5. Arrancar la aplicacion

```bash
cd code/backendBotTrading
mvn spring-boot:run
```

Tambien puedes compilar el jar:

```bash
mvn clean package
```

## Flujo De Uso Recomendado

Un recorrido tipico dentro de la CLI es este:

```text
signup
login
mkpwallet
fetch
le
backtest
train
optimize
trade -v o trade -r
```

## Comandos CLI Destacados

| Categoria | Comando | Uso |
| --- | --- | --- |
| Usuarios | `signup`, `login`, `logout` | alta, autenticacion y cierre de sesion |
| Carteras | `mkpwallet`, `lw` | crea y lista paper wallets |
| Mercado | `fetch` | descarga velas historicas y rellena huecos |
| Estrategias | `le` | lista estrategias disponibles |
| Backtesting | `backtest` | simulacion historica clasica o con `-model` |
| Tiempo real | `trade`, `lsa`, `lsd`, `start`, `stop`, `term` | ciclo de vida de sesiones de paper trading |
| IA | `models`, `train`, `optimize` | catalogo, entrenamiento y busqueda de hiperparametros |

Ejemplos representativos:

```bash
# Descargar historico 1h para BTC y ETH
fetch -tf 1h -coins BTCUSDT ETHUSDT -d 365 --detect-gaps

# Ejecutar backtest clasico
backtest -strategy StressTestStrategy -tf 1h -coins BTCUSDT

# Entrenar un modelo
train -model lightgbm -tf 1h -coins BTCUSDT -strategy StressTestStrategy -d 365

# Optimizar hiperparametros
optimize -model lightgbm -tf 1h -coins BTCUSDT -strategy StressTestStrategy -d 365 -n-trials 30 -cv-folds 5

# Lanzar paper trading en tiempo real
trade -v -strategy StressTestStrategy -tf 1h -coins BTCUSDT
```

## Validacion Y Calidad

Todo lo que sigue se ejecuta automaticamente en cada pull request y en cada push a `main`
(`.github/workflows/ci.yml`), en tres jobs: Python, Java y validacion de esquema contra
MySQL 8 real.

| Comprobacion | Comando | Cobertura actual |
| --- | --- | --- |
| Backend Java | `cd code/backendBotTrading && mvn verify` | 347 tests + Checkstyle + PMD |
| Engines Python | `pytest code/scripts/tests -q` | 184 tests |
| Linting Python | `ruff check code/` | configurado en `ruff.toml` |
| Tipos Python | `mypy code/scripts` | configurado en `mypy.ini` |

Las versiones de las herramientas se fijan en `requirements.txt` y su configuracion vive en el
repositorio: sin eso el conjunto de reglas dependeria de la version instalada en cada maquina y
el CI juzgaria el codigo con un criterio distinto al local.

`mvn verify` incluye ademas **SpotBugs** (effort `Max`, umbral `High`) y genera el informe de
cobertura de JaCoCo, que el CI publica como artefacto del run.

### Auditoria de dependencias

OWASP dependency-check esta **deliberadamente fuera** de `mvn verify` (sus `executions` estan
comentadas en el `pom.xml`): la API del NVD es intermitente y no debe tumbar la validacion de un
PR. Corre en su propio workflow, `.github/workflows/security-audit.yml`, cada lunes y bajo
demanda, junto a `pip-audit` sobre `requirements.txt`. Ninguno bloquea; ambos dejan su informe
como artefacto.

```bash
# Manualmente, tal y como documenta el pom
cd code/backendBotTrading && mvn dependency-check:check -N
```

> Define el secreto `NVD_API_KEY` en el repositorio para que el analisis no quede limitado por
> el rate limit del NVD. Sin el funciona, pero mucho mas lento.

### Esquema de base de datos

```bash
# Entidades JPA contra las migraciones Flyway (H2 en modo MySQL)
mvn test -Dtest=SchemaMigrationValidationTest

# Contra MySQL 8 real: cubre BIT(1), DATETIME(6), DECIMAL, AUTO_INCREMENT y claves foraneas,
# que H2 en modo compatibilidad no reproduce. Requiere una base dedicada y vacia.
mvn test -Dtest=SchemaMigrationMySqlIntegrationTest -Dintegration.db=true
```

> Los tests con `@SpringBootTest` **se cuelgan**: `AppBot` implementa `CommandLineRunner` y
> arranca la CLI interactiva. Para persistencia usa `@DataJpaTest`.

### E2E de entrenamiento

```bash
bash code/backendBotTrading/src/test/resources/e2e/train_neural_network_e2e.sh
```

## Referencias Del Proyecto

- Esquema de base de datos: `code/backendBotTrading/src/main/resources/db/migration/`
- Guia de trabajo y contratos internos (IPC, estrategias): `CLAUDE.md`
- Configuracion Maven y calidad: `code/backendBotTrading/pom.xml`
- Configuracion Sonar: `code/backendBotTrading/sonar-project.properties`

> `info/tablas.sql` se conserva como **referencia historica y esta desactualizado** (le faltan
> `instancia_estrategia.nombre_modelo` y `posicion.{precio_salida, pnl, fecha_cierre}`). La
> fuente de verdad del esquema son las migraciones Flyway.

> Nota: este snapshot del repositorio se centra en el codigo, los resultados y los artefactos de ejecucion. La memoria academica del TFG no forma parte de la raiz actual del proyecto.

## Artefactos Generados

Durante la ejecucion del proyecto se generan carpetas con bastante volumen:

- `models/` para modelos entrenados y metadatos,
- `results/` para CSVs de backtesting y optimizacion,
- `logs/` para logs de engines y sesiones.

Estos directorios son utiles para experimentacion y analisis, pero no conviene tratarlos como documentacion fuente ni subir artefactos pesados sin revisarlos antes.

## Estado Del Proyecto

CryptoApp es un proyecto academico real, funcional y con una base tecnica considerablemente mayor que la de una demo. Su objetivo principal no es vender una promesa de rentabilidad, sino ofrecer una infraestructura reproducible para estudiar estrategias cuantitativas, medir resultados y discutirlos con rigor.

## Autor

**Alejandro Lopez Lopez**  
Grado en Ingenieria Informatica  
Universidad de Murcia

---

> Este repositorio tiene fines academicos y experimentales. No constituye asesoramiento financiero ni una recomendacion de inversion.
