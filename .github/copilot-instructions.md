# Copilot Instructions — CryptoApp

> **La fuente de verdad es [`../CLAUDE.md`](../CLAUDE.md) y el código.** Este archivo se
> mantiene deliberadamente corto: la versión anterior eran ~500 líneas que duplicaban la guía
> principal y acabaron describiendo una arquitectura que ya no existía (TSV, `pandas-ta`,
> PyTorch, `poblar_indicadores`, `engine_paper_trade.py`). Dos documentos largos en paralelo
> divergen; uno corto que delega, no.

## Qué es el proyecto

Plataforma de trading algorítmico sobre cripto (TFG, Universidad de Murcia). Java orquesta
(CLI, sesión, persistencia, contabilidad, ciclo de vida de procesos); Python calcula (fetch,
indicadores, backtest, entrenamiento, optimización, inferencia en tiempo real).
**Paper trading únicamente — nunca dinero real.**

## Lo mínimo que hay que saber antes de tocar código

| Tema | Regla |
|---|---|
| Stack | Java 21 + Spring Boot 3.3.5; Python 3.11 con `ta` (no `pandas-ta`) y TensorFlow/Keras (no PyTorch) |
| CLI | Implementación propia en `interfaces/cli/` — **no** es Spring Shell |
| Esquema BD | Lo versiona **Flyway** (`src/main/resources/db/migration`); Hibernate en `ddl-auto=validate` |
| Arquitectura | Un paquete por dominio, cada uno con `application/domain/infrastructure`; `domain` no depende de `infrastructure` |
| Dinero | `BigDecimal` en Java y `Decimal` en Python, nunca `double`/`float` |
| IPC | Dos protocolos que **no deben mezclarse**: MessagePack con framing de 4 bytes para request/response, y líneas de texto (`SIGNAL\t<json>`, `HEARTBEAT`) para tiempo real |
| stdout | En engines con framing, escribir texto suelto en stdout **corrompe el frame**. Los logs van a stderr |
| Tests | `@SpringBootTest` **se cuelga** (la CLI arranca en `CommandLineRunner`). Usa `@DataJpaTest` |

El detalle completo de cada punto —contrato IPC, contrato de estrategias, convenciones de
commits— está en [`../CLAUDE.md`](../CLAUDE.md). No lo repitas aquí.

## Calidad

Cada PR ejecuta `.github/workflows/ci.yml`: `mvn verify` (Checkstyle, PMD, 347 tests JUnit),
`ruff check code/`, `mypy code/scripts` y `pytest code/scripts/tests` (184 tests), más la
validación de las migraciones contra MySQL 8 real. La configuración de ruff y mypy está
fijada en el repositorio (`ruff.toml`, `mypy.ini`) y sus versiones en `requirements.txt`.

## Commits

Máximo dos líneas, formato `feat|fix|wip|docs|data: <resumen>`.
