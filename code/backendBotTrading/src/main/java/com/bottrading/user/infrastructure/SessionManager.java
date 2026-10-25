package com.bottrading.user.infrastructure;

import org.springframework.stereotype.Component;

import com.bottrading.user.domain.Usuario;

import lombok.extern.slf4j.Slf4j;

/**
 * Gestiona el estado de la sesión del usuario.
 */
@Slf4j
@Component
public class SessionManager {

    private Usuario currentUser;


    public void login(Usuario usuario) {
        this.currentUser = usuario;
    }

    public void logout() {
        if (currentUser == null) {
            log.info("No hay sesión activa para cerrar.");
            return;
        }

        log.info("Cerrando sesión de {}...", currentUser.getNombre());
        this.currentUser = null;
        log.info("Sesión cerrada.");

    }

    public Usuario getCurrentUser() {
        return currentUser;
    }

    public boolean isLoggedIn() {
        return currentUser != null;
    }
}
