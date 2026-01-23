package com.bottrading.beans;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "usuario")
public class Usuario extends BaseEntity {

    @Column(nullable = false, unique = true)
    private String nombre;

    @Column(nullable = false)
    private String passwordHash;

    protected Usuario() {
        super();
    }

    public Usuario(String nombre, String passwordHash) {
        super();
        this.nombre = nombre;
        this.passwordHash = passwordHash;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getNombre() {
        return nombre;
    }

    @Override
    public String toString() {
        return "Usuario [nombre=" + nombre + ", passwordHash=" + passwordHash + "]";
    }

}
