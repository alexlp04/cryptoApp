package com.bottrading.domain.user;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.bottrading.domain.user.Usuario;

@Repository
public interface UsuarioRepository extends JpaRepository<Usuario, Long> {

    // Sustituye existeUsuario
    boolean existsByNombre(String nombre);

    // Sustituye obtenerUsuarioPorNombre
    Optional<Usuario> findByNombreAndEliminadoFalse(String nombre);

    // Búsqueda por ID con validación de activo
    Optional<Usuario> findByIdAndEliminadoFalse(Long id);
}