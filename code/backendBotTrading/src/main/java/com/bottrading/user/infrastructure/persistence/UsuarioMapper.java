package com.bottrading.user.infrastructure.persistence;

import com.bottrading.user.domain.Usuario;
import com.bottrading.user.infrastructure.persistence.UsuarioJpaEntity;
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
        Usuario usuario = new Usuario(
            jpaEntity.getNombre(),
            jpaEntity.getPasswordHash()
        );
        usuario.setId(jpaEntity.getId());
        return usuario;
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
        UsuarioJpaEntity entity = new UsuarioJpaEntity(
            domain.getNombre(),
            domain.getPasswordHash()
        );
        entity.setId(domain.getId());
        return entity;
    }
}
