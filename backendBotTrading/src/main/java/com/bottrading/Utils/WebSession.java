package com.bottrading.Utils;

import java.util.HashMap;
import java.util.Map;

import com.bottrading.beans.Usuario;
import com.bottrading.controllers.ControladorUsuario;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;

public class WebSession {

    private static WebSession instance;
    private EntityManagerFactory emf;
    private static Usuario currentUser;
    private ControladorUsuario controladorUsuario;

    private WebSession() {
        String dbUrl = System.getenv("DB_URL_TFG");
        String dbUser = System.getenv("DB_USER_TFG");
        String dbPass = System.getenv("DB_PASS_TFG");
        Map<String, String> props = new HashMap<>();
        props.put("javax.persistence.jdbc.url", dbUrl);
        props.put("javax.persistence.jdbc.user", dbUser);
        props.put("javax.persistence.jdbc.password", dbPass);
        this.emf = Persistence.createEntityManagerFactory("botTradingPU", props);
        this.controladorUsuario = new ControladorUsuario();
    }

    public static synchronized WebSession getInstance() {
        if (instance == null) {
            instance = new WebSession();
        }
        return instance;
    }

    public EntityManager getEntityManager() {
        return emf.createEntityManager();
    }

    public Usuario getCurrentUser() {
        return currentUser;
    }

    public void setCurrentUser(Usuario user) {

        WebSession.currentUser = user;
    }

    public boolean signup(String nombre, String password) {
        if (controladorUsuario.existeUsuario(nombre)) {
            System.out.println("El usuario ya existe.");
            return false;
        }
        currentUser = controladorUsuario.crearUsuario(nombre, password);
        return currentUser != null;

    }

    public boolean login(String nombre, String password) {
        if (controladorUsuario.validarCredenciales(nombre, password)) {
            Usuario usuario = controladorUsuario.obtenerUsuarioPorNombre(nombre);
            setCurrentUser(usuario);
            return true;
        }
        return false;

    }

    public boolean isLoggedIn() {
        return currentUser != null;
    }

}
