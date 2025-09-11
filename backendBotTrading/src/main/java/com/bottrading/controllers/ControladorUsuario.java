package com.bottrading.controllers;

import com.bottrading.beans.Usuario;
import com.bottrading.daos.UsuarioDAO;

public class ControladorUsuario {

    private UsuarioDAO usuarioDAO;

    public ControladorUsuario() {
        usuarioDAO = UsuarioDAO.getInstance();
    }

    public Usuario crearUsuario(String email, String nombre, String password) {
        return usuarioDAO.crearUsuario(email, nombre, password);
    }

    public boolean existeUsuario(String email) {
        return usuarioDAO.existeUsuario(email);
    }



}
