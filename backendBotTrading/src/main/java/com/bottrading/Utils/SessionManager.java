package com.bottrading.utils;

import com.bottrading.beans.Usuario;
import org.springframework.stereotype.Component;

/**
 * Gestiona el estado de la sesión del usuario y la carga de configuración de
 * entorno.
 */
@Component
public class SessionManager {

    private Usuario currentUser;

    // --- Lógica de Sesión ---

    public void login(Usuario usuario) {
        this.currentUser = usuario;
    }

    public void logout() {
        this.currentUser = null;
    }

    public Usuario getCurrentUser() {
        return currentUser;
    }

    public boolean isLoggedIn() {
        return currentUser != null;
    }
}