package com.bottrading.shared.exceptions;

/**
 * Excepción lanzada cuando hay errores al procesar señales.
 */
public class SignalProcessingException extends TradingServiceException {
    public SignalProcessingException(String message) {
        super(message);
    }

    public SignalProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
