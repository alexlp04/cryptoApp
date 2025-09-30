package com.bottrading.Utils;

import java.util.HashMap;
import java.util.Map;

import com.bottrading.beans.Email;
import com.bottrading.beans.Usuario;
import com.bottrading.controllers.ControladorEmail;
import com.bottrading.controllers.ControladorUsuario;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;

public class WebSession {

    private static WebSession instance;
    private EntityManagerFactory emf;
    private Usuario currentUser;
    private Email currentUserEmail;
    private ControladorUsuario controladroUsuario = new ControladorUsuario();
    private ControladorEmail controladorEmail = new ControladorEmail();

    private WebSession() {
        String dbUrl = System.getenv("DB_URL_TFG");
        String dbUser = System.getenv("DB_USER_TFG");
        String dbPass = System.getenv("DB_PASS_TFG");
        Map<String, String> props = new HashMap<>();
        props.put("javax.persistence.jdbc.url", dbUrl);
        props.put("javax.persistence.jdbc.user", dbUser);
        props.put("javax.persistence.jdbc.password", dbPass);
        this.emf = Persistence.createEntityManagerFactory("botTradingPU", props);
    }

    public static WebSession getInstance() {
        if (instance == null) {
            instance = new WebSession();
        }
        return instance;
    }

    public EntityManager getEntityManager() {
        return emf.createEntityManager();
    }

    public Email getCurrentUserEmail() {
        return currentUserEmail;
    }

    public void setCurrentUserEmail(Email email) {
        this.currentUserEmail = email;
    }

    public Usuario getCurrentUser() {
        return currentUser;
    }

    public void setCurrentUser(Usuario user) {
        this.currentUser = user;
    }

    public boolean signup(String nombre, String email, String password) {
        if (controladroUsuario.existeUsuario(email, nombre)) {
            System.out.println("El usuario o email ya existen.");
            return false;
        }
        currentUser = controladroUsuario.crearUsuario(nombre, password);
        if (currentUser != null) {
            currentUserEmail = controladorEmail.crearEmail(email, currentUser);
            return true;
        } else {
            return false;
        }
    }

    public boolean login(String email, String password) {
        if (!controladorEmail.existeEmail(email)) {
            return false;
        }
        if (controladroUsuario.validarCredenciales(email, password)) {
            Usuario usuario = controladroUsuario.obtenerUsuarioPorEmail(email);
            setCurrentUser(usuario);
            setCurrentUserEmail(controladorEmail.obtenerEmail(email));
            return true;
        }
        return false;

    }

}
