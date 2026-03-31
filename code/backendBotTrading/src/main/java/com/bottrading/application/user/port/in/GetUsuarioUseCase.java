package com.bottrading.application.user.port.in;

import com.bottrading.domain.user.Usuario;
import java.util.Optional;

/**
 * PUERTO DE ENTRADA - Caso de uso para obtener usuario.
 */
public interface GetUsuarioUseCase {

    /**
     * Obtiene un usuario por nombre.
     */
    Optional<Usuario> getByNombre(String nombre);

    /**
     * Obtiene un usuario por ID.
     */
    Optional<Usuario> getById(Long id);
}
