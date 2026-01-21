package com.bottrading.daos;

import com.bottrading.beans.Wallet;
import com.bottrading.beans.Usuario;
import com.bottrading.beans.WalletType;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.NoResultException;

import java.util.List;

public class WalletDAO extends BaseEntityDAO<Wallet> {

    private static WalletDAO instance;

    private WalletDAO() {
        super(Wallet.class);
    }

    public static synchronized WalletDAO getInstance() {
        if (instance == null) {
            instance = new WalletDAO();
        }
        return instance;
    }

    // Obtener wallet con lock pesimista (para operaciones de trading)
    public Wallet findByIdWithLock(Long id) {
        try {
            return getEntityManager().find(Wallet.class, id, LockModeType.PESSIMISTIC_WRITE);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public Wallet findByIdWithLock(EntityManager em, Long id) {
    try {
        // Usamos el 'em' que nos pasan, que ya tiene la transacción iniciada
        return em.find(Wallet.class, id, LockModeType.PESSIMISTIC_WRITE);
    } catch (Exception e) {
        e.printStackTrace();
        return null;
    }
}

    // Buscar por usuario
    public List<Wallet> findByUsuario(Usuario usuario) {
        try {
            return getEntityManager()
                .createQuery("SELECT w FROM Wallet w WHERE w.usuario = :usuario ORDER BY w.nombre", Wallet.class)
                .setParameter("usuario", usuario)
                .getResultList();
        } catch (Exception e) {
            e.printStackTrace();
            return List.of();
        } finally {
            close();
        }
    }

    // Buscar por usuario y tipo
    public List<Wallet> findByUsuarioAndType(Usuario usuario, WalletType type) {
        try {
            return getEntityManager()
                .createQuery("SELECT w FROM Wallet w WHERE w.usuario = :usuario AND w.type = :type ORDER BY w.nombre", Wallet.class)
                .setParameter("usuario", usuario)
                .setParameter("type", type)
                .getResultList();
        } catch (Exception e) {
            e.printStackTrace();
            return List.of();
        } finally {
            close();
        }
    }

    // Buscar wallets disponibles (no activas) por tipo
    public List<Wallet> findAvailableByType(Usuario usuario, WalletType type) {
        try {
            return getEntityManager()
                .createQuery(
                    "SELECT w FROM Wallet w WHERE w.usuario = :usuario AND w.type = :type AND w.isActive = false ORDER BY w.nombre",
                    Wallet.class)
                .setParameter("usuario", usuario)
                .setParameter("type", type)
                .getResultList();
        } catch (Exception e) {
            e.printStackTrace();
            return List.of();
        } finally {
            close();
        }
    }

    // Buscar por nombre
    public Wallet findByNombre(Usuario usuario, String nombre) {
        try {
            List<Wallet> resultados = getEntityManager()
                .createQuery("SELECT w FROM Wallet w WHERE w.usuario = :usuario AND w.nombre = :nombre", Wallet.class)
                .setParameter("usuario", usuario)
                .setParameter("nombre", nombre)
                .getResultList();
            
            return resultados.isEmpty() ? null : resultados.get(0);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        } finally {
            close();
        }
    }

    public Wallet findByNombre(EntityManager em, Usuario usuario, String nombre) {
    try {
        return em.createQuery("SELECT w FROM Wallet w WHERE w.usuario = :u AND w.nombre = :n", Wallet.class)
                 .setParameter("u", usuario)
                 .setParameter("n", nombre)
                 .getSingleResult();
    } catch (NoResultException e) {
        return null;
    }
}

    // Verificar si existe
    public boolean exists(Usuario usuario, String nombre) {
        try {
            Long count = (Long) getEntityManager()
                .createQuery("SELECT COUNT(w) FROM Wallet w WHERE w.usuario = :usuario AND w.nombre = :nombre")
                .setParameter("usuario", usuario)
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
}