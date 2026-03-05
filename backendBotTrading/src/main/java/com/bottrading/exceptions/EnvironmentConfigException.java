package com.bottrading.exceptions;

/**
 * Excepción lanzada cuando hay problemas con la configuración del archivo .env
 * o cuando faltan variables de entorno requeridas.
 */
public class EnvironmentConfigException extends RuntimeException {
    
    public EnvironmentConfigException(String message) {
        super(message);
    }
    
    public EnvironmentConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
