package com.bottrading.daos;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;

public class BaseEntityDAO<T> {

    private final Class<T> entityClass;

    // Constructor que recibe la clase de la entidad
    public BaseEntityDAO(Class<T> entityClass) {
        this.entityClass = entityClass;
    }

    public T save(EntityManager em, T entity) {
        EntityTransaction tx = em.getTransaction();
        try {
            tx.begin();
            em.persist(entity);
            tx.commit();
            return entity;
        } catch (Exception e) {
            if (tx.isActive()) tx.rollback();
            e.printStackTrace();
            return null;
        }
    }

    public T update(EntityManager em, T entity) {
        EntityTransaction tx = em.getTransaction();
        try {
            tx.begin();
            T merged = em.merge(entity);
            tx.commit();
            return merged;
        } catch (Exception e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        }
    }

    public void delete(EntityManager em, T entity) {
        EntityTransaction tx = em.getTransaction();
        try {
            em.getTransaction().begin();
            em.remove(em.contains(entity) ? entity : em.merge(entity));
            em.getTransaction().commit();
        } catch (Exception e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        }
    }

    public T findById(EntityManager em, Object id) {
        return em.find(entityClass, id);
    }
}
