package com.bottrading.user.application.port.in;

import com.bottrading.user.domain.Usuario;

/**
 * PUERTO DE ENTRADA - Caso de uso para crear usuario.
 * 
 * Define el contrato que los controllers u otros adaptadores de entrada
 * pueden usar para crear usuarios en el sistema.
 * 
 * Implementación: application/user/UsuarioApplicationService
 */
public interface CreateUsuarioUseCase {

    /**
     * Crea un nuevo usuario.
     * 
     * @param nombre nombre del usuario
     * @param passwordHash hash de la contraseña
     * @return usuario creado
     * @throws IllegalArgumentException si los parámetros son inválidos
     * @throws RuntimeException si el usuario ya existe
     */
    Usuario create(String nombre, String password);

    /**
     * Alias explicito para flujo CLI.
     */
    default Usuario registrar(String nombre, String password) {
        return create(nombre, password);
    }
}
