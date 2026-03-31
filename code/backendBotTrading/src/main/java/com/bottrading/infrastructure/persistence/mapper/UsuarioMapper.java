package com.bottrading.infrastructure.persistence.mapper;

import com.bottrading.domain.user.Usuario;
import com.bottrading.infrastructure.persistence.entity.UsuarioJpaEntity;
import org.springframework.stereotype.Component;

/**
 * MAPPER — Convierte entre dominio y persistencia.
 * 
 * Responsabilidad:
 *   Usuario (domain) ←→ UsuarioJpaEntity (jpa)
 * 
 * Este patrón evita que el código de negocio conozca sobre JPA.
 */
@Component
public class UsuarioMapper {

    /**
     * Convierte entidad JPA a entidad de dominio.
     * @param jpaEntity entidad JPA de base de datos
     * @return usuario de dominio
     */
    public Usuario toDomain(UsuarioJpaEntity jpaEntity) {
        if (jpaEntity == null) {
            return null;
        }
        return new Usuario(
            jpaEntity.getNombre(),
            jpaEntity.getPasswordHash()
        );
    }

    /**
     * Convierte entidad de dominio a entidad JPA.
     * @param domain usuario de dominio
     * @return usuario JPA para persistencia
     */
    public UsuarioJpaEntity toJpa(Usuario domain) {
        if (domain == null) {
            return null;
        }
        return new UsuarioJpaEntity(
            domain.getNombre(),
            domain.getPasswordHash()
        );
    }
}
