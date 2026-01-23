package com.bottrading.daos;

import com.bottrading.beans.Usuario;

public class UsuarioDAO extends BaseEntityDAO<Usuario> {

    private static UsuarioDAO instance;

    private UsuarioDAO() {
        super(Usuario.class);
    }

    public static synchronized UsuarioDAO getInstance() {
        if (instance == null) {
            instance = new UsuarioDAO();
        }
        return instance;
    }

    // Crear usuario
    public Usuario crearUsuario(String nombre, String passwordHash) {
        Usuario usuario = new Usuario(nombre, passwordHash);
        try {
            return save(usuario);
        } finally {
            close();
        }
    }

    // Verificar si existe usuario
    public boolean existeUsuario(String nombre) {
        try {
            Long count = (Long) getEntityManager()
                .createQuery("SELECT COUNT(u) FROM Usuario u WHERE u.nombre = :nombre")
                .setParameter("nombre", nombre)
                .getSingleResult();
            
            return count > 0;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        } finally {
            close();
        }
    }

    // Obtener usuario por nombre
    public Usuario obtenerUsuarioPorNombre(String nombre) {
        try {
            return getEntityManager()
                .createQuery(
                    "SELECT u FROM Usuario u WHERE u.nombre = :nombre AND u.eliminado = false",
                    Usuario.class)
                .setParameter("nombre", nombre)
                .getSingleResult();
        } catch (Exception e) {
            return null;
        } finally {
            close();
        }
    }

    // Obtener usuario por ID con verificación
    public Usuario obtenerUsuarioActivo(Long id) {
        try {
            Usuario usuario = findById(id);
            return (usuario != null && !usuario.isEliminado()) ? usuario : null;
        } finally {
            close();
        }
    }

    // Marcar usuario como eliminado (soft delete)
    public boolean eliminarUsuario(Long id) {
        try {
            beginTransaction();
            Usuario usuario = findById(id);
            
            if (usuario != null) {
                usuario.setEliminado(true);
                updateWithoutTransaction(usuario);
                commitTransaction();
                return true;
            }
            
            rollbackTransaction();
            return false;
        } catch (Exception e) {
            rollbackTransaction();
            e.printStackTrace();
            return false;
        } finally {
            close();
        }
    }
}