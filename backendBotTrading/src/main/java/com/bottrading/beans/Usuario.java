package com.bottrading.beans;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "usuario")
public class Usuario extends BaseEntity {

    private String password;

    public Usuario() {
        // Hibernate necesita constructor vacío
    }

    public Usuario(String nombre, String password) {
        setNombre(nombre);
        this.password = password;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
