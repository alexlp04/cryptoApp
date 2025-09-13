package com.bottrading.Utils;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;

public class WebSession {

    private static WebSession instance;
    private EntityManagerFactory emf;
    private String currentUserEmail;

    private WebSession() {
        EntityManagerFactory emf = Persistence.createEntityManagerFactory("botTradingPU");
    }

    public static WebSession getInstance() {
        if (instance == null) {
            instance = new WebSession();
        }
        return instance;
    }
 
    public EntityManager getEntityManager() {
        return emf.createEntityManager();
    }

    public String getCurrentUserEmail() {
        return currentUserEmail;
    }

    public void setCurrentUserEmail(String email) {
        this.currentUserEmail = email;
    }

}
