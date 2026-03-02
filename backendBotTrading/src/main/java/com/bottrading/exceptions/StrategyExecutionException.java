package com.bottrading.exceptions;

/**
 * Excepción lanzada cuando hay errores en la ejecución de estrategias.
 */
public class StrategyExecutionException extends TradingServiceException {
    public StrategyExecutionException(String message) {
        super(message);
    }

    public StrategyExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
