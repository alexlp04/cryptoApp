package com.bottrading.user.application.port.in;

import com.bottrading.user.domain.Usuario;
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
