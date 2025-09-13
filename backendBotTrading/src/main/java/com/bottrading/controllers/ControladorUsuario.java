package com.bottrading.controllers;

import com.bottrading.Utils.HashUtils;
import com.bottrading.beans.Usuario;
import com.bottrading.daos.EmailDAO;
import com.bottrading.daos.UsuarioDAO;

public class ControladorUsuario {

    private UsuarioDAO usuarioDAO;

    public ControladorUsuario() {
        usuarioDAO = UsuarioDAO.getInstance();
    }

    public Usuario crearUsuario(String nombre, String password) {
        return usuarioDAO.crearUsuario(nombre, HashUtils.hashPassword(password));
    }

    public boolean existeUsuario(String email, String nombre) {
        return usuarioDAO.existeUsuario(nombre) && EmailDAO.getInstance().existeEmail(email);
    }

    public Usuario obtenerUsuarioPorEmail(String email) {
        return usuarioDAO.obtenerUsuarioPorEmail(email);
    }

    public boolean validarCredenciales(String loginEmail, String loginPassword) {
        Usuario usuario = obtenerUsuarioPorEmail(loginEmail);
        if (usuario != null) {
            return HashUtils.verificarPassword(loginPassword, usuario.getPassword());
        }
        return false;
    }

}
