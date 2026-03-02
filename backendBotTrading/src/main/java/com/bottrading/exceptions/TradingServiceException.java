package com.bottrading.exceptions;

/**
 * Excepción base para errores en el servicio de trading.
 */
public class TradingServiceException extends RuntimeException {
    public TradingServiceException(String message) {
        super(message);
    }

    public TradingServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
