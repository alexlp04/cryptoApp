### `TradingService.java:301` y `TradingService.java:310`
`procesarSignalesPendientes` puede entrar en bucle infinito: si `paperTradingService.onSignal` falla siempre, haces `poll()` y luego `offer()` dentro de `while (!cola.isEmpty())`.
* **Impacto:** hilo bloqueado, consumo CPU, shutdown difícil, estrategia “atascada”.
* **Cambio recomendado:** procesar un snapshot de tamaño fijo (`int n = cola.size()`), reencolar en una cola secundaria con backoff y límite de intentos.

---

### `BacktestingService.java:134` `BacktestingService.java:135` `BacktestingService.java:136` `BacktestingService.java:137` `BacktestingService.java:138` `BacktestingService.java:174` `BacktestingService.java:175`
Conversión de `BigDecimal` a `double` para precios, volumen, capital y riesgo.
* **Impacto:** pérdida de precisión financiera y posibles discrepancias de PnL/backtest.
* **Cambio recomendado:** serializar como `String` (o decimal exacto) y parsear en Python con `Decimal`.

---

### `AITrainingService.java:82` y `MarketDataService.java:85`
`@Transactional` envuelve flujos largos con I/O externo (Python, fetch masivo, cálculos).
* **Impacto:** transacciones largas, locks innecesarios, riesgo de contención y rollback costoso.
* **Cambio recomendado:** transacciones cortas solo alrededor de operaciones DB concretas; separar orquestación de negocio de persistencia transaccional.

---

### `BacktestingService.java:152` `BacktestingService.java:154` `BacktestingService.java:157` y `FileService.java:237` `FileService.java:238` `FileService.java:240` `FileService.java:246`
Servicios de capa negocio haciendo I/O interactivo (`System.out`, `System.in`, `Scanner`).
* **Impacto:** rompe separación de capas (CLI vs service), dificulta tests y automatización.
* **Cambio recomendado:** mover prompts al comando CLI/Shell, que el service reciba parámetros ya decididos.

---

### `SessionManager.java:35`
Posible `NullPointerException` en `logout()` si no hay usuario logueado (`currentUser.getNombre()`).
* **Impacto:** error en logout y mala robustez de sesión.
* **Cambio recomendado:** null-check defensivo.

---

### `TradingService.java:49`
`Executors.newCachedThreadPool()` sin límite para estrategias en tiempo real.
* **Impacto:** crecimiento no acotado de hilos bajo carga/picos.
* **Cambio recomendado:** `newVirtualThreadPerTaskExecutor()` (Java 21) o pool acotado con backpressure.

---

### `MarketDataService.java:47`
`parallelStream()` para fetch paralelo sin control explícito del pool.
* **Impacto:** compite por `ForkJoinPool.commonPool`, comportamiento impredecible con otras tareas.
* **Cambio recomendado:** usar `Executor` dedicado del contexto Spring (idealmente virtual threads) y `CompletableFuture`.

---

### `TradingService.java:196` y `TradingService.java:238`
Al usar `redirectErrorStream(true)`, cualquier línea stderr que empiece con `{` puede tratarse como señal JSON válida.
* **Impacto:** señales falsas, ejecuciones erróneas de buy/sell.
* **Cambio recomendado:** separar canales o usar protocolo explícito (prefijo `SIGNAL\t...`).

---

### `IndicatorsService.java:173`
Filtro con `idsValidos.contains(dto.getId())` donde `idsValidos` es lista.
* **Impacto:** complejidad O(n^2) en lotes grandes.
* **Cambio recomendado:** `Set<Long>` para lookup O(1).

---

### `PaperTradingService.java:169` y `PaperTradingService.java:171`
`win_rate` persistido como string con `%` y `fecha_fin` con `new Date().toString()`.
* **Impacto:** formato no estable y difícil de explotar analíticamente.
* **Cambio recomendado:** guardar `winRate` numérico (`BigDecimal`) y timestamp ISO-8601 UTC.

---

### `EstrategiaService.java:247`
`sumCapitalActivoByWallet` se maneja como `Double` y luego `BigDecimal.valueOf(sum)`.
* **Impacto:** arrastre de precisión binaria en agregados monetarios.
* **Cambio recomendado:** retornar `BigDecimal` desde repositorio/query.

---

### `FetchService.java:115` y `IndicatorsService.java:91`
Complejidad cognitiva alta (también reportada por análisis estático).
* **Impacto:** mantenimiento difícil, mayor riesgo de bugs en retries/timeouts.
* **Cambio recomendado:** extraer submétodos por responsabilidad (start process, stream IO, wait/timeout, retry policy).

---

## Observaciones de estructura por capas

**Bien encaminado:** existe separación entre servicios de dominio (`AccountingService`, `WalletService`, etc.) y orquestadores (`EstrategiaService`, `MarketDataService`).

**Rupturas actuales:**
* `BacktestingService` y `FileService` mezclan lógica de negocio con interacción de consola.
* Integración Python está distribuida en varios servicios (`TradingService`, `FetchService`, `IndicatorsService`, `AITrainingService`, `BacktestingService`) con lógica repetida de `ProcessBuilder`, timeout, retries y streams.

**Recomendación arquitectónica:** centralizar en un `PythonBridge` único (o capa `bridge/`) con contrato estándar.

---

## Plan de cambios priorizado

### P0 (bloqueantes funcionales y de riesgo financiero, hacer primero)
* Corregir bucle de reintentos infinito en `TradingService`.
* Eliminar conversiones `BigDecimal` -> `double` del flujo de backtest.
* Sacar I/O interactivo de servicios y llevarlo a capa CLI.
* Acotar `@Transactional` a bloques de persistencia cortos.

### P1 (estabilidad operativa y rendimiento)
* Sustituir `newCachedThreadPool` y `parallelStream` por ejecutores gestionados (preferible virtual threads).
* Endurecer protocolo Java↔Python para separar logs de señales.
* Optimizar `IndicatorsService` usando `Set` para validación de IDs.
* Corregir `logout()` defensivo y homogeneizar manejo de errores de dominio.

### P2 (calidad y mantenibilidad)
* Unificar formatos de métricas/fechas (`BigDecimal` + ISO-8601 UTC).
* Eliminar `Double` en agregados monetarios y repositorios.
* Refactor por complejidad cognitiva de métodos largos.
* Consolidar integración de procesos Python en un componente reusable.

### P3 (hardening y observabilidad)
* Añadir tests de concurrencia y resiliencia (retries, timeouts, shutdown).
* Añadir tests de precisión financiera (round-trip Java↔Python).
* Añadir métricas: tiempo por proceso Python, ratio de retries, señales descartadas.