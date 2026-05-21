package com.bottrading.user.infrastructure.persistence;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * SPRING DATA JPA REPOSITORY - Acceso a datos de Usuario.
 * 
 * Esta interfaz trabaja DIRECTAMENTE con entidades JPA.
 * NUNCA devuelve objetos de dominio, siempre JpaEntity.
 * 
 * El adapter de persistencia se encarga de convertir JpaEntity → Domain.
 */
@Repository
public interface UsuarioJpaRepository extends JpaRepository<UsuarioJpaEntity, Long> {

    Optional<UsuarioJpaEntity> findByNombre(String nombre);

    Optional<UsuarioJpaEntity> findByNombreAndEliminadoFalse(String nombre);

    Optional<UsuarioJpaEntity> findByIdAndEliminadoFalse(Long id);

    boolean existsByNombre(String nombre);
}
