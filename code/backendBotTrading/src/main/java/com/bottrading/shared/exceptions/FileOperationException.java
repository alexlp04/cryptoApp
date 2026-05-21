package com.bottrading.shared.exceptions;

/**
 * Excepción lanzada cuando hay errores en operaciones de archivos.
 */
public class FileOperationException extends TradingServiceException {
    public FileOperationException(String message) {
        super(message);
    }

    public FileOperationException(String message, Throwable cause) {
        super(message, cause);
    }
}
