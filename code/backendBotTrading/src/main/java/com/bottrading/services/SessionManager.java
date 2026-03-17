package com.bottrading.services;

import com.bottrading.beans.Usuario;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Gestiona el estado de la sesión del usuario y la carga de configuración de
 * entorno.
 */
@Slf4j
@Component
public class SessionManager {

    private Usuario currentUser;

    private final EstrategiaService estrategiaService;

    @Autowired
    public SessionManager(EstrategiaService estrategiaService) {
        this.estrategiaService = estrategiaService;
    }

    // --- Lógica de Sesión ---

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

    @PreDestroy
    public void cleanup() {
        log.info("Cerrando todas las estrategias antes de apagar el sistema...");
        // Delegamos la limpieza al servicio que tiene el mapa
        estrategiaService.terminarTodas();

    }
}