package com.bottrading.beans;

import jakarta.persistence.Entity;

@Entity
public class Usuario extends BaseEntity {

    private String email;
    private String password;

    public Usuario() {
        // Hibernate necesita constructor vacío
    }

    public Usuario(String nombre, String email, String password) {
        setNombre(nombre);
        this.email = email;
        this.password = password;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
