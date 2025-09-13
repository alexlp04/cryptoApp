package com.bottrading.daos;

import com.bottrading.Utils.WebSession;
import com.bottrading.beans.Usuario;
import jakarta.persistence.EntityManager;

public class UsuarioDAO extends BaseEntityDAO<com.bottrading.beans.Usuario> {

    private static UsuarioDAO instance;

    public UsuarioDAO() {
        super(com.bottrading.beans.Usuario.class);
    }

    public static UsuarioDAO getInstance() {
        if (instance == null) {
            instance = new UsuarioDAO();
        }
        return instance;
    }

    public Usuario crearUsuario(String nombre, String password) {
        Usuario nuevoUsuario = new Usuario(nombre, password);
        EntityManager em = WebSession.getInstance().getEntityManager();
        return save(em, nuevoUsuario);
    }

    public boolean existeUsuario(String nombre) {
        EntityManager em = WebSession.getInstance().getEntityManager();

        try {
            long count = em.createQuery("SELECT COUNT(u) FROM Usuario u WHERE u.nombre = :nombre", Long.class)
                    .setParameter("nombre", nombre)
                    .getSingleResult();
            return count > 0;
        } finally {
            if (em != null)
                em.close();
        }
    }

    public Usuario obtenerUsuarioPorEmail(String email) {
        EntityManager em = WebSession.getInstance().getEntityManager();
        try {
            return em.createQuery("SELECT u FROM Usuario u WHERE u.id = (SELECT e.usuario.id FROM Email e WHERE e.email = :email AND e.eliminado = false) AND u.eliminado = false", Usuario.class)
                    .setParameter("email", email)
                    .getSingleResult();
        } catch (Exception e) {
            return null;
        } finally {
            if (em != null)
                em.close();
        }
    }

}
