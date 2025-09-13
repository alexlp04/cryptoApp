package com.bottrading.controllers;

import com.bottrading.beans.Usuario;
import com.bottrading.daos.EmailDAO;
import com.bottrading.daos.UsuarioDAO;

public class ControladorUsuario {

    private UsuarioDAO usuarioDAO;

    public ControladorUsuario() {
        usuarioDAO = UsuarioDAO.getInstance();
    }

    public Usuario crearUsuario(String nombre, String password) {
        return usuarioDAO.crearUsuario(nombre, password);
    }

    public boolean existeUsuario(String email, String nombre) {
        return usuarioDAO.existeUsuario(nombre) && EmailDAO.getInstance().existeEmail(email);
    }



}
