package com.bottrading.beans;

public class Email extends BaseEntity{
    
    private String email;
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