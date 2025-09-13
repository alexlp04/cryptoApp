package com.bottrading.daos;

import com.bottrading.Utils.WebSession;
import com.bottrading.beans.Email;

import jakarta.persistence.EntityManager;

public class EmailDAO extends BaseEntityDAO<Email> {

    private static EmailDAO instance;

    public EmailDAO() {
        super(Email.class);
    }

    public static EmailDAO getInstance() {
        if (instance == null) {
            instance = new EmailDAO();
        }
        return instance;
    }

        public boolean existeEmail(String email) {
        EntityManager em = WebSession.getInstance().getEntityManager();

        try {
            long count = em.createQuery("SELECT COUNT(e) FROM Email e WHERE e.eliminado = false AND e.email = :email", Long.class)
                    .setParameter("email", email)
                    .getSingleResult();
            return count > 0;
        } finally {
            if (em != null)
                em.close();
        }
    }


    
}
