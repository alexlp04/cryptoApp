package com.bottrading.config;

/**
 * Centraliza la configuración de procesos Javaava-Python.
 * Proporciona constantes de resiliencia, timeouts y parámetros de serialización.
 *
 * Estándar compartido entre BacktestingService, FetchService,
 * AITrainingService y TradingService.
 */
public final class ProcessExecutorConfig {

    private ProcessExecutorConfig() {
    }

    // RESILIENCIA
    /** Timeout legacy para procesos Python en segundos. Usar solo cuando se quiera límite total explícito. */
    public static final int TIMEOUT_SECONDS = 30;

    /** Inactividad máxima tolerada en entrenamiento de modelos. */
    public static final int TRAIN_INACTIVITY_TIMEOUT_SECONDS = 1800;

    /** Inactividad máxima tolerada en optimización de hiperparámetros (Optuna). */
    public static final int OPTIMIZE_INACTIVITY_TIMEOUT_SECONDS = 3600;

    /** Número máximo de reintentos automáticos en caso de fallo. */
    public static final int MAX_RETRIES = 3;
}
