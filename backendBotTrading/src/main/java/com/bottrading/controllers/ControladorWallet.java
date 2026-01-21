package com.bottrading.controllers;

import com.bottrading.Utils.WebSession;
import com.bottrading.beans.Usuario;
import com.bottrading.beans.Wallet;
import com.bottrading.beans.WalletType;
import com.bottrading.daos.WalletDAO;

import jakarta.persistence.EntityManager;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class ControladorWallet {

    private final WalletDAO walletDAO = WalletDAO.getInstance();
    
    // Mantenemos los locks para sincronización a nivel de JVM antes de ir a DB
    private static final ConcurrentHashMap<String, Object> walletLocks = new ConcurrentHashMap<>();

    /* ===================== CREAR / ELIMINAR ===================== */

    public boolean crearWallet(String nombre, double balanceInicial, boolean isReal) {
        Usuario usuario = WebSession.getInstance().getCurrentUser();
        if (usuario == null) return false;

        if (walletDAO.exists(usuario, nombre)) {
            System.out.println("Ya existe una wallet con ese nombre.");
            return false;
        }

        Wallet wallet = new Wallet();
        wallet.setNombre(nombre);
        wallet.setUsuario(usuario);
        wallet.setBalance(balanceInicial);
        wallet.setType(isReal ? WalletType.REAL : WalletType.PAPER);
        wallet.setActive(false);

        // El método save de BaseEntityDAO ya maneja su propia transacción
        walletDAO.save(wallet);
        return true;
    }

    public boolean eliminarWallet(String nombre) {
        Usuario usuario = WebSession.getInstance().getCurrentUser();
        if (usuario == null) return false;

        Wallet wallet = walletDAO.findByNombre(usuario, nombre);
        if (wallet == null) {
            System.out.println("Wallet no encontrada.");
            return false;
        }

        if (wallet.isActive()) {
            System.out.println("No puedes eliminar una wallet que está siendo usada por el bot.");
            return false;
        }

        walletDAO.delete(wallet);
        walletLocks.remove(nombre);
        return true;
    }

    /* ===================== ESTADO ACTIVA (PARA TRADING) ===================== */

    public boolean setWalletActiva(String nombre) {
        Usuario usuario = WebSession.getInstance().getCurrentUser();
        if (usuario == null) return false;

        // Iniciamos transacción manual para asegurar atomicidad al activar
        walletDAO.beginTransaction();
        try {
            Wallet wallet = walletDAO.findByNombre(usuario, nombre);
            if (wallet == null || wallet.isActive()) {
                walletDAO.rollbackTransaction();
                return false;
            }

            wallet.setActive(true);
            // Usamos el método protegido expuesto por tu BaseDAO
            walletDAO.update(wallet); 
            walletLocks.computeIfAbsent(nombre, k -> new Object());
            return true;
        } catch (Exception e) {
            walletDAO.rollbackTransaction();
            return false;
        }
    }

    public boolean unsetWalletActiva(String nombre) {
        Usuario usuario = WebSession.getInstance().getCurrentUser();
        if (usuario == null) return false;

        walletDAO.beginTransaction();
        try {
            Wallet wallet = walletDAO.findByNombre(usuario, nombre);
            if (wallet != null) {
                wallet.setActive(false);
                walletDAO.update(wallet);
            }
            walletDAO.commitTransaction();
            walletLocks.remove(nombre);
            return true;
        } catch (Exception e) {
            walletDAO.rollbackTransaction();
            return false;
        }
    }

    /* ===================== CONSULTAS ===================== */

    public double getBalance(String nombre) {
        Usuario usuario = WebSession.getInstance().getCurrentUser();
        if (usuario == null) return 0.0;
        Wallet w = walletDAO.findByNombre(usuario, nombre);
        return (w != null) ? w.getBalance() : 0.0;
    }

    public List<String> listarWallets() {
        Usuario usuario = WebSession.getInstance().getCurrentUser();
        if (usuario == null) return List.of();

        return walletDAO.findByUsuario(usuario).stream()
                .map(w -> String.format("%s | Balance: %.2f | Tipo: %s %s",
                        w.getNombre(), w.getBalance(), w.getType(), 
                        w.isActive() ? "[EN USO]" : ""))
                .collect(Collectors.toList());
    }

    public List<String> listarWalletsDisponibles(boolean real) {
        Usuario usuario = WebSession.getInstance().getCurrentUser();
        if (usuario == null) return List.of();
        
        WalletType type = real ? WalletType.REAL : WalletType.PAPER;
        return walletDAO.findAvailableByType(usuario, type).stream()
                .map(w -> String.format("%s | Balance: %.2f", w.getNombre(), w.getBalance()))
                .collect(Collectors.toList());
    }

    /* ===================== TRADING SEGURO (CON LOCKS) ===================== */

    public boolean intentarCompra(String walletNombre, double montoTotal) {
        Usuario usuario = WebSession.getInstance().getCurrentUser();
        if (usuario == null) return false;

        Object lock = walletLocks.computeIfAbsent(walletNombre, k -> new Object());

        synchronized (lock) {
            // Creamos UN solo EM para toda esta operación
            EntityManager em = WebSession.getInstance().getEntityManager(); 
            try {
                em.getTransaction().begin();

                // Pasamos el 'em' activo a los métodos del DAO
                Wallet wallet = walletDAO.findByNombre(em, usuario, walletNombre);
                if (wallet == null) throw new Exception("Wallet no existe");

                // Bloqueo pesimista sobre el mismo 'em'
                wallet = walletDAO.findByIdWithLock(em, wallet.getId());
                
                if (wallet.getBalance() < montoTotal) {
                    em.getTransaction().rollback();
                    return false;
                }

                wallet.setBalance(wallet.getBalance() - montoTotal);
                // No hace falta llamar a update si la entidad está gestionada por el 'em' activo
                
                em.getTransaction().commit();
                return true;
            } catch (Exception e) {
                if (em.getTransaction().isActive()) em.getTransaction().rollback();
                System.err.println("Error en compra: " + e.getMessage());
                return false;
            } finally {
                em.close(); // Cerramos el recurso al finalizar
            }
        }
    }
    public void registrarVenta(String walletNombre, double montoRecibido) {
        Usuario usuario = WebSession.getInstance().getCurrentUser();
        if (usuario == null) return;

        Object lock = walletLocks.computeIfAbsent(walletNombre, k -> new Object());

        synchronized (lock) {
            // 1. Obtenemos un EntityManager local para esta operación
            EntityManager em = WebSession.getInstance().getEntityManager(); 
            try {
                em.getTransaction().begin();

                // 2. Buscamos la wallet usando el EM activo de esta transacción
                Wallet walletTmp = walletDAO.findByNombre(em, usuario, walletNombre);
                if (walletTmp == null) {
                    System.err.println("❌ Error: Wallet " + walletNombre + " no encontrada para venta.");
                    em.getTransaction().rollback();
                    return;
                }

                // 3. Bloqueo pesimista sobre el mismo EM (Esto ya NO fallará)
                Wallet wallet = walletDAO.findByIdWithLock(em, walletTmp.getId());

                if (wallet != null) {
                    // 4. Actualizar balance
                    wallet.setBalance(wallet.getBalance() + montoRecibido);
                    
                    // 5. Confirmar transacción
                    em.getTransaction().commit();
                    // System.out.println("✅ [DB] Balance actualizado en " + walletNombre);
                }
            } catch (Exception e) {
                if (em.getTransaction().isActive()) em.getTransaction().rollback();
                System.err.println("❌ Error crítico al registrar venta en DB: " + e.getMessage());
            } finally {
                // 6. Cerrar el recurso SIEMPRE
                if (em.isOpen()) em.close();
            }
        }
    }
}