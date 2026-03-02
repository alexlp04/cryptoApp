package com.bottrading.services;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.bottrading.beans.Usuario;
import com.bottrading.exceptions.ValidationException;
import com.bottrading.repositories.UsuarioRepository;
import com.bottrading.utils.HashUtils;

import jakarta.transaction.Transactional;

/**
 * Servicio encargado de la gestión de usuarios, autenticación y ciclo de vida de cuentas.
 * Implementa lógica de seguridad básica (hashing) y borrado lógico (soft delete).
 */
@Service
public class UsuarioService {

    private final UsuarioRepository usuarioRepo;

    @Autowired
    public UsuarioService(UsuarioRepository usuarioRepo) {
        this.usuarioRepo = usuarioRepo;
    }
    
    /**
     * Registra un nuevo usuario en el sistema.
     * Verifica duplicados y cifra la contraseña antes de persistir.
     *
     * @param nombre   Nombre de usuario (debe ser único).
     * @param password Contraseña en texto plano.
     * @return El objeto Usuario creado y persistido.
     * @throws RuntimeException Si el nombre de usuario ya está en uso.
     */
    public Usuario registrar(String nombre, String password) {
        if (usuarioRepo.existsByNombre(nombre)) {
            throw new ValidationException("El nombre de usuario ya está en uso");
        }
        Usuario u = new Usuario(nombre, HashUtils.hashPassword(password));
        u.setEliminado(false);
        return usuarioRepo.save(u);
    }

    

    /**
     * Valida las credenciales de acceso de un usuario.
     * Compara la contraseña proporcionada con el hash almacenado.
     *
     * @param nombre   Nombre del usuario.
     * @param password Contraseña a verificar.
     * @return true si las credenciales son válidas y el usuario no está eliminado.
     */
    public boolean validarCredenciales(String nombre, String password) {
        return usuarioRepo.findByNombreAndEliminadoFalse(nombre)
                .map(u -> HashUtils.verificarPassword(password, u.getPasswordHash()))
                .orElse(false);
    }

    /**
     * Realiza un borrado lógico (Soft Delete) del usuario.
     * Marca el registro como eliminado en lugar de borrarlo físicamente.
     *
     * @param id Identificador del usuario.
     */
    @Transactional
    public void darDeBaja(long id) {
        usuarioRepo.findById(id).ifPresent(u -> {
            u.setEliminado(true);
            usuarioRepo.save(u);
        });
    }

    /**
     * Busca un usuario activo por su nombre.
     *
     * @param nombre Nombre del usuario.
     * @return El usuario encontrado.
     * @throws RuntimeException Si el usuario no existe o ha sido dado de baja.
     */
    @Transactional
    public Usuario obtenerPorNombre(String nombre) {
        return usuarioRepo.findByNombreAndEliminadoFalse(nombre)
                .orElseThrow(() -> new ValidationException("Usuario no encontrado o dado de baja"));
    }
}