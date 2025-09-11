package com.bottrading.daos;

import com.bottrading.Utils.WebSession;
import com.bottrading.beans.Usuario;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

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

    public Usuario crearUsuario(String email, String nombre, String password) {
        Usuario nuevoUsuario = new Usuario(nombre, email, password);
        EntityManager em = WebSession.getInstance().getEntityManager();
        return save(em, nuevoUsuario);
    }

    public boolean existeUsuario(String email) {
        EntityManager em = WebSession.getInstance().getEntityManager();

        try {
            long count = em.createQuery("SELECT COUNT(u) FROM Usuario u WHERE u.email = :email", Long.class)
                    .setParameter("email", email)
                    .getSingleResult();
            return count > 0;
        } finally {
            if (em != null)
                em.close();
        }
    }

}
