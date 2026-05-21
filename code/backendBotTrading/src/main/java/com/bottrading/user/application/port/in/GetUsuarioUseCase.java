package com.bottrading.user.application.port.in;

import java.util.Optional;

import com.bottrading.user.domain.Usuario;

/**
 * PUERTO DE ENTRADA - Caso de uso para obtener usuario.
 */
public interface GetUsuarioUseCase {

    /**
     * Obtiene un usuario por nombre.
     */
    Optional<Usuario> getByNombre(String nombre);

    /**
     * Alias explicito para flujo CLI.
     */
    default Usuario obtenerPorNombre(String nombre) {
        return getByNombre(nombre)
                .orElseThrow(() -> new IllegalArgumentException("Usuario no encontrado o dado de baja"));
    }

    /**
     * Obtiene un usuario por ID.
     */
    Optional<Usuario> getById(Long id);
}
