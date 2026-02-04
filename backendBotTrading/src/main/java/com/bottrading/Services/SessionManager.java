package com.bottrading.services;

import com.bottrading.beans.Usuario;

import jakarta.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Gestiona el estado de la sesión del usuario y la carga de configuración de
 * entorno.
 */
@Component
public class SessionManager {

    private Usuario currentUser;

    @Autowired
    private TradingService tradingService;

    // --- Lógica de Sesión ---

    public void login(Usuario usuario) {
        this.currentUser = usuario;
    }

    public void logout() {
        System.out.println("Cerrando sesión de " + currentUser.getNombre() + "...");

        this.currentUser = null;
        tradingService.detenerTodo();
                                System.out.println("Sesión cerrada.");

    }

    public Usuario getCurrentUser() {
        return currentUser;
    }

    public boolean isLoggedIn() {
        return currentUser != null;
    }

    @PreDestroy
    public void cleanup() {
        System.out.println("Cerrando todas las estrategias antes de apagar el sistema...");
        // Delegamos la limpieza al servicio que tiene el mapa
        tradingService.detenerTodo();
    }
}