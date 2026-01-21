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

    @Column(nullable = false)
    private boolean active = true;

    protected Usuario() {
    }

    public Usuario(String nombre, String passwordHash) {
        this.nombre = nombre;
        this.passwordHash = passwordHash;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    @Override
    public String toString() {
        return "Usuario [nombre=" + nombre + ", passwordHash=" + passwordHash + ", active=" + active + "]";
    }

    

}
