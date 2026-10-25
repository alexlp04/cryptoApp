package com.bottrading.user.infrastructure.persistence;

import com.bottrading.shared.domain.BaseEntity;
import java.time.Instant;

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

    @Column(name = "intentos_fallidos", nullable = false)
    private int intentosFallidos;

    @Column(name = "bloqueado_hasta")
    private Instant bloqueadoHasta;

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

    public int getIntentosFallidos() {
        return intentosFallidos;
    }

    public void setIntentosFallidos(int intentosFallidos) {
        this.intentosFallidos = intentosFallidos;
    }

    public Instant getBloqueadoHasta() {
        return bloqueadoHasta;
    }

    public void setBloqueadoHasta(Instant bloqueadoHasta) {
        this.bloqueadoHasta = bloqueadoHasta;
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
