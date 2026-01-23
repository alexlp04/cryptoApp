package com.bottrading.utils;

import com.bottrading.beans.Usuario;
import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;

/**
 * Gestiona el estado de la sesión del usuario y la carga de configuración de
 * entorno.
 */
@Component
public class SessionManager {

    private Usuario currentUser;

    /**
     * Este método se ejecuta automáticamente al iniciar Spring.
     * Carga el archivo .env y lo inyecta en las Propiedades del Sistema de Java.
     */
    @PostConstruct
    public void loadEnv() {
        try {
            Dotenv dotenv = Dotenv.configure()
                    .directory("./backendBotTrading") // Busca en la raíz del proyecto
                    .ignoreIfMissing()
                    .load();

            // Seteamos las variables en el Sistema para que Spring las vea en
            // application.properties
            dotenv.entries().forEach(entry -> {
                System.setProperty(entry.getKey(), entry.getValue());
            });

            System.out.println("✅ Configuración de entorno (.env) cargada correctamente.");
        } catch (Exception e) {
            System.err.println("⚠️ No se pudo cargar el archivo .env: " + e.getMessage());
        }
    }

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