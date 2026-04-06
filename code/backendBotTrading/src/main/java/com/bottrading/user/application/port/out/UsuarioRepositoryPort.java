package com.bottrading.user.application.port.out;

import com.bottrading.user.domain.Usuario;
import java.util.Optional;
import java.util.List;

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
     * Busca un usuario por nombre.
     */
    Optional<Usuario> findByNombre(String nombre);

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
