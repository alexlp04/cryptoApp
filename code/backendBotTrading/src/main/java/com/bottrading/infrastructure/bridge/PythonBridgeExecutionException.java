package com.bottrading.infrastructure.bridge;

/**
 * Excepción de infraestructura para fallos durante la ejecución de procesos Python.
 */
public class PythonBridgeExecutionException extends Exception {

    public PythonBridgeExecutionException(String message) {
        super(message);
    }

    public PythonBridgeExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
