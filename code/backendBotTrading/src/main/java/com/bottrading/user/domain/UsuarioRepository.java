package com.bottrading.user.domain;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.bottrading.user.infrastructure.persistence.UsuarioJpaEntity;

@Repository
public interface UsuarioRepository extends JpaRepository<UsuarioJpaEntity, Long> {

    // Sustituye existeUsuario
    boolean existsByNombre(String nombre);

    // Sustituye obtenerUsuarioPorNombre
    Optional<UsuarioJpaEntity> findByNombreAndEliminadoFalse(String nombre);

    // Búsqueda por ID con validación de activo
    Optional<UsuarioJpaEntity> findByIdAndEliminadoFalse(Long id);
}