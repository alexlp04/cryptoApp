package com.bottrading.exceptions;

/**
 * Excepción lanzada cuando hay errores en la ejecución de procesos Python.
 */
public class PythonProcessException extends TradingServiceException {
    public PythonProcessException(String message) {
        super(message);
    }

    public PythonProcessException(String message, Throwable cause) {
        super(message, cause);
    }
}
