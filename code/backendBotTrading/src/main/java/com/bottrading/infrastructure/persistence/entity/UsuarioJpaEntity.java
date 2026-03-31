package com.bottrading.infrastructure.persistence.entity;

import com.bottrading.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * ADAPTER JPA — Entidad de persistencia para Usuario.
 * 
 * Esta clase contiene TODAS las anotaciones JPA.
 * El dominio (domain/user/Usuario.java) es una clase pura, sin JPA.
 * 
 * Flujo de conversión:
 *   JPA entity ←-mapper-→ Domain entity
 */
@Entity
@Table(name = "usuario")
public class UsuarioJpaEntity extends BaseEntity {

    @Column(nullable = false, unique = true)
    private String nombre;

    @Column(nullable = false)
    private String passwordHash;

    protected UsuarioJpaEntity() {
        super();
    }

    public UsuarioJpaEntity(String nombre, String passwordHash) {
        super();
        this.nombre = nombre;
        this.passwordHash = passwordHash;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getNombre() {
        return nombre;
    }

    public void setNombre(String nombre) {
        this.nombre = nombre;
    }

    @Override
    public String toString() {
        return "UsuarioJpaEntity [nombre=" + nombre + "]";
    }
}
