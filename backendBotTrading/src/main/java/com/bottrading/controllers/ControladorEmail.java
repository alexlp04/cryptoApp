package com.bottrading.controllers;

import com.bottrading.Utils.WebSession;
import com.bottrading.beans.Email;
import com.bottrading.beans.Usuario;
import com.bottrading.daos.EmailDAO;

import jakarta.persistence.EntityManager;

public class ControladorEmail {
    
    private EmailDAO emailDAO;

    public ControladorEmail() {
        emailDAO = EmailDAO.getInstance();
    }

    public boolean existeEmail(String email) {
        return emailDAO.existeEmail(email);
    }

    public Email crearEmail(String email, Usuario usuario) {
        Email nuevoEmail = new Email(email, usuario);
        EntityManager em = WebSession.getInstance().getEntityManager();
        return emailDAO.save(em, nuevoEmail);
    }

    public Email obtenerEmail(String email) {
        EntityManager em = WebSession.getInstance().getEntityManager();
        try {
            return em.createQuery("SELECT e FROM Email e WHERE e.eliminado = false AND e.email = :email", Email.class)
                    .setParameter("email", email)
                    .getSingleResult();
        } finally {
            if (em != null)
                em.close();
        }
    }
    
}
