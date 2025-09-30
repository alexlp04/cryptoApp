package com.bottrading.beans;

import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "email")
public class Email extends BaseEntity{
    
    private String email;

    @OneToOne
    @JoinColumn(name = "usuario_id")
    private Usuario usuario;

    public Email() {
    }

    public Email(String email, Usuario usuario) {
        this.email = email;
        this.usuario = usuario;
    }

    public String getEmail() {
        return email;
    }

    public Usuario getUsuario() {
        return usuario;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public void setUsuario(Usuario usuario) {
        this.usuario = usuario;
    }
}