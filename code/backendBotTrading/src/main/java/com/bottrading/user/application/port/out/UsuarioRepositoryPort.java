package com.bottrading.user.application.port.out;

import java.util.List;
import java.util.Optional;

import com.bottrading.user.domain.Usuario;

/**
 * PUERTO DE SALIDA — Abstración de persistencia para Usuario.
 * 
 * Define qué operaciones de persistencia necesita la aplicación.
 * La implementación concreta está en infrastructure/persistence/adapter/.
 * 
 * Flujo:
 *   Application Service → UsuarioRepositoryPort (interfaz)
 *                      → UsuarioPersistenceAdapter (implementación)
 *                      → JPA Repository
 */
public interface UsuarioRepositoryPort {

    /**
     * Busca un usuario por ID.
     */
    Optional<Usuario> findById(Long id);

    /**
     * Busca un usuario activo por ID.
     */
    Optional<Usuario> findByIdAndEliminadoFalse(Long id);

    /**
     * Busca un usuario por nombre.
     */
    Optional<Usuario> findByNombre(String nombre);

    /**
     * Busca un usuario activo por nombre.
     */
    Optional<Usuario> findByNombreAndEliminadoFalse(String nombre);

    /**
     * Persiste un usuario.
     */
    Usuario save(Usuario usuario);

    /**
     * Elimina un usuario.
     */
    void deleteById(Long id);

    /**
     * Lista todos los usuarios.
     */
    List<Usuario> findAll();
}
