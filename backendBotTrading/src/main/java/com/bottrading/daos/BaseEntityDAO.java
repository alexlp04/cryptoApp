package com.bottrading.daos;

import java.util.HashMap;
import java.util.Map;

import io.github.cdimascio.dotenv.Dotenv;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;
import jakarta.persistence.Persistence;

public class BaseEntityDAO<T> {

protected static final EntityManagerFactory emf;
    protected final Class<T> entityClass;
    protected ThreadLocal<EntityManager> entityManagerThreadLocal = new ThreadLocal<>();

    static {
        try {
            Dotenv dotenv = Dotenv.configure()
                            .directory("./backendBotTrading")
                            .load();
            Map<String, String> properties = new HashMap<>();
            properties.put("jakarta.persistence.jdbc.url", dotenv.get("DB_URL"));
            properties.put("jakarta.persistence.jdbc.user", dotenv.get("DB_USER"));
            properties.put("jakarta.persistence.jdbc.password", dotenv.get("DB_PASSWORD"));
            properties.put("jakarta.persistence.jdbc.driver", "com.mysql.cj.jdbc.Driver");
            
            emf = Persistence.createEntityManagerFactory("botTradingPU", properties);

        } catch (Exception e) {
            System.err.println("❌ Error inicializando DB: " + e.getMessage());
            throw new ExceptionInInitializerError(e);
        }
    }

    public BaseEntityDAO(Class<T> entityClass) {
        this.entityClass = entityClass;
    }

    protected EntityManager getEntityManager() {
        EntityManager em = entityManagerThreadLocal.get();
        if (em == null || !em.isOpen()) {
            em = emf.createEntityManager();
            entityManagerThreadLocal.set(em);
        }
        return em;
    }

    public void close() {
        EntityManager em = entityManagerThreadLocal.get();
        if (em != null && em.isOpen()) {
            em.close();
            entityManagerThreadLocal.remove();
        }
    }

    public void beginTransaction() {
        EntityTransaction tx = getEntityManager().getTransaction();
        if (!tx.isActive()) {
            tx.begin();
        }
    }

    public void commitTransaction() {
        EntityManager em = entityManagerThreadLocal.get();
        if (em != null) {
            EntityTransaction tx = em.getTransaction();
            if (tx != null && tx.isActive()) {
                tx.commit();
            }
        }
    }

    public void rollbackTransaction() {
        EntityManager em = entityManagerThreadLocal.get();
        if (em != null) {
            EntityTransaction tx = em.getTransaction();
            if (tx != null && tx.isActive()) {
                tx.rollback();
            }
        }
    }

    // CRUD con transacciones automáticas
    public T save(T entity) {
        try {
            beginTransaction();
            getEntityManager().persist(entity);
            commitTransaction();
            return entity;
        } catch (Exception e) {
            rollbackTransaction();
            e.printStackTrace();
            throw new RuntimeException("Error guardando entidad", e);
        }
    }

    public T update(T entity) {
        try {
            beginTransaction();
            T merged = getEntityManager().merge(entity);
            commitTransaction();
            return merged;
        } catch (Exception e) {
            rollbackTransaction();
            throw new RuntimeException("Error actualizando entidad", e);
        }
    }

    public void delete(T entity) {
        try {
            beginTransaction();
            EntityManager em = getEntityManager();
            em.remove(em.contains(entity) ? entity : em.merge(entity));
            commitTransaction();
        } catch (Exception e) {
            rollbackTransaction();
            throw new RuntimeException("Error eliminando entidad", e);
        }
    }

    public T findById(Object id) {
        try {
            return getEntityManager().find(entityClass, id);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    // Métodos sin transacciones (para usar dentro de transacciones manuales)
    protected T saveWithoutTransaction(T entity) {
        getEntityManager().persist(entity);
        return entity;
    }

    protected T updateWithoutTransaction(T entity) {
        return getEntityManager().merge(entity);
    }

    public static void shutdown() {
        if (emf != null && emf.isOpen()) {
            emf.close();
            System.out.println("EntityManagerFactory cerrado");
        }
    }
}