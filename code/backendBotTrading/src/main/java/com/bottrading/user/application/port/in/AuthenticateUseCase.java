package com.bottrading.user.application.port.in;

import java.util.Optional;

import com.bottrading.user.domain.Usuario;

/**
 * PUERTO DE ENTRADA - Caso de uso para autenticacion.
 */
public interface AuthenticateUseCase {

    /**
     * Valida credenciales del usuario y, si son correctas, lo devuelve (una sola consulta).
     */
    Optional<Usuario> autenticar(String nombre, String password);
}
