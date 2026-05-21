package com.bottrading.shared.exceptions;

/**
 * Excepción lanzada cuando hay errores al obtener datos.
 */
public class DataFetchException extends TradingServiceException {
    public DataFetchException(String message) {
        super(message);
    }

    public DataFetchException(String message, Throwable cause) {
        super(message, cause);
    }
}
