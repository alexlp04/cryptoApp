package com.bottrading.user.application.port.in;

/**
 * PUERTO DE ENTRADA - Caso de uso para autenticacion.
 */
public interface AuthenticateUseCase {

    /**
     * Valida credenciales del usuario.
     */
    boolean validarCredenciales(String nombre, String password);
}
