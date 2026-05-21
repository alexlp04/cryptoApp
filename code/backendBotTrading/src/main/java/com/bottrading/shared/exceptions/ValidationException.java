package com.bottrading.shared.exceptions;

/**
 * Excepción lanzada cuando hay errores de validación de datos.
 */
public class ValidationException extends TradingServiceException {
    public ValidationException(String message) {
        super(message);
    }

    public ValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
