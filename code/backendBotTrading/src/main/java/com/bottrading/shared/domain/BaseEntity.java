package com.bottrading.shared.domain;

import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;

@MappedSuperclass
public abstract class BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "fecha_creacion")
    private Instant fechaCreacion;
    @Column(nullable = false)
    private boolean eliminado;

    protected BaseEntity() {
        this.fechaCreacion = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Instant getFechaCreacion() {
        return fechaCreacion;
    }

    public boolean isEliminado() {
        return eliminado;
    }

    public void setEliminado(boolean eliminado) {
        this.eliminado = eliminado;
    }

    /**
     * Dos entidades son iguales si tienen el mismo tipo y el mismo ID persistido.
     * Las entidades transientes (id == null) solo son iguales a sí mismas.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BaseEntity that = (BaseEntity) o;
        return id != null && Objects.equals(id, that.id);
    }

    /**
     * hashCode basado en clase para mantener el contrato incluso antes de persistir.
     */
    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
