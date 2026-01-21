package com.bottrading.controllers;

import com.bottrading.Utils.HashUtils;
import com.bottrading.beans.Usuario;
import com.bottrading.daos.UsuarioDAO;

public class ControladorUsuario {

    private final UsuarioDAO usuarioDAO;

    public ControladorUsuario() {
        this.usuarioDAO = UsuarioDAO.getInstance();
    }

    /* ===================== GESTIÓN DE USUARIOS ===================== */

    public Usuario crearUsuario(String nombre, String password) {
        return usuarioDAO.crearUsuario(nombre, HashUtils.hashPassword(password));
    }

    public boolean existeUsuario(String nombre) {
        return usuarioDAO.existeUsuario(nombre);
    }

    public Usuario obtenerUsuarioPorNombre(String nombre) {
        return usuarioDAO.obtenerUsuarioPorNombre(nombre);
    }

    /* ===================== AUTENTICACIÓN ===================== */

public boolean validarCredenciales(String loginNombre, String loginPassword) {
    Usuario usuario = usuarioDAO.obtenerUsuarioPorNombre(loginNombre);
    
    if (usuario != null) {
        String dbHash = usuario.getPasswordHash();
        return HashUtils.verificarPassword(loginPassword, dbHash);
    }
    return false;
}

    public boolean darDeBajaUsuario(Long id) {
        // Delegamos el soft-delete al DAO
        return usuarioDAO.eliminarUsuario(id);
    }
}