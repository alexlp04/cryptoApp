package com.bottrading.config;

/**
 * Centraliza la configuración de procesos Javaava-Python.
 * Proporciona constantes de resiliencia, timeouts y parámetros de serialización.
 *
 * Estándar compartido entre BacktestingService, IndicatorsService, FetchService,
 * AITrainingService y TradingService.
 */
public final class ProcessExecutorConfig {

    private ProcessExecutorConfig() {
    }

    // RESILIENCIA
    /** Timeout legacy para procesos Python en segundos. Usar solo cuando se quiera límite total explícito. */
    public static final int TIMEOUT_SECONDS = 30;

    /** Tiempo máximo para que el proceso Python emita su primera señal de vida. */
    public static final int STARTUP_TIMEOUT_SECONDS = 120;

    /** Inactividad máxima tolerada en operaciones batch de sincronización/cálculo. */
    public static final int BATCH_INACTIVITY_TIMEOUT_SECONDS = 300;

    /** Inactividad máxima tolerada en backtesting. */
    public static final int BACKTEST_INACTIVITY_TIMEOUT_SECONDS = 900;

    /** Inactividad máxima tolerada en entrenamiento de modelos. */
    public static final int TRAIN_INACTIVITY_TIMEOUT_SECONDS = 1800;

    /** Número máximo de reintentos automáticos en caso de fallo. */
    public static final int MAX_RETRIES = 3;

    /** Delay entre reintentos en milisegundos. */
    public static final long RETRY_DELAY_MS = 1000;

    // SERIALIZACIÓN
    /** Tamaño de chunk para streaming en registros. */
    public static final int CHUNK_SIZE = 10000;

    /** Habilitar compresión GZIP en serialización MessagePack. */
    public static final boolean COMPRESSION_ENABLED = true;

    /** Codificación de caracteres estándar. */
    public static final String CHARSET = "UTF-8";

    // PROCESOS PYTHON
    /** Número de threads para ExecutorService (BacktestingService, etc). */
    public static final int EXECUTOR_THREADS = 4;

    /** Esperar confirmación de lectura en stream. Delay en ms. */
    public static final long STREAM_READ_DELAY_MS = 50;

    // LOGGING Y DIAGNÓSTICO
    /** Log cada N registros procesados. */
    public static final int LOG_INTERVAL_RECORDS = 5000;

    /** Mostrar métricas de serialización (tamaño original vs comprimido). */
    public static final boolean DEBUG_SERIALIZATION_METRICS = true;
}
