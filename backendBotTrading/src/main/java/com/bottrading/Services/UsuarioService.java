package com.bottrading.services;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.bottrading.beans.Usuario;
import com.bottrading.repositories.UsuarioRepository;
import com.bottrading.utils.HashUtils;

import jakarta.transaction.Transactional;

@Service
public class UsuarioService {

    @Autowired
    private UsuarioRepository usuarioRepo; // Tu interfaz JpaRepository

    public Usuario registrar(String nombre, String password) {
        if (usuarioRepo.existsByNombre(nombre)) {
            throw new RuntimeException("El nombre de usuario ya está en uso");
        }
        Usuario u = new Usuario(nombre, HashUtils.hashPassword(password));
        u.setEliminado(false);
        return usuarioRepo.save(u);
    }

    public boolean validarCredenciales(String nombre, String password) {
        return usuarioRepo.findByNombreAndEliminadoFalse(nombre)
                .map(u -> HashUtils.verificarPassword(password, u.getPasswordHash()))
                .orElse(false);
    }

    @Transactional
    public void darDeBaja(Long id) {
        usuarioRepo.findById(id).ifPresent(u -> {
            u.setEliminado(true);
            usuarioRepo.save(u);
        });
    }

    @Transactional
    public Usuario obtenerPorNombre(String nombre) {
        return usuarioRepo.findByNombreAndEliminadoFalse(nombre)
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado o dado de baja"));
    }
}