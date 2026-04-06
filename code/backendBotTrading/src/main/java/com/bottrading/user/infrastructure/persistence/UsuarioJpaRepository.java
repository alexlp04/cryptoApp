package com.bottrading.user.infrastructure.persistence;

import com.bottrading.user.infrastructure.persistence.UsuarioJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

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

    boolean existsByNombre(String nombre);
}
