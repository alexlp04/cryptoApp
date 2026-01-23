package com.bottrading.daos;

import com.bottrading.beans.InstanciaEstrategia;
import jakarta.persistence.TypedQuery;
import java.util.List;

public class InstanciaEstrategiaDAO extends BaseEntityDAO<InstanciaEstrategia> {

    private static InstanciaEstrategiaDAO instance;

    private InstanciaEstrategiaDAO() {
        super(InstanciaEstrategia.class);
    }

    public static synchronized InstanciaEstrategiaDAO getInstance() {
        if (instance == null) {
            instance = new InstanciaEstrategiaDAO();
        }
        return instance;
    }

    /**
     * Calcula la suma del capital asignado actual de todas las instancias 
     * que están en estado 'ACTIVA' para una wallet específica.
     */
    public double sumCapitalActivoByWallet(String nombreWallet) {
        try {
            TypedQuery<Double> query = getEntityManager().createQuery(
                "SELECT SUM(i.capitalAsignadoActual) FROM InstanciaEstrategia i " +
                "WHERE i.walletAsociada = :nombreWallet AND i.estado = 'ACTIVA'", 
                Double.class
            );
            query.setParameter("nombreWallet", nombreWallet);
            Double result = query.getSingleResult();
            return (result != null) ? result : 0.0;
        } catch (Exception e) {
            e.printStackTrace();
            return 0.0;
        }
    }

    /**
     * Actualiza el capital asignado actual de una instancia de forma persistente.
     * Se usa para el interés compuesto local en PaperTradingService.
     */
    public void updateCapital(Long instanciaId, double nuevoCapital) {
        try {
            beginTransaction();
            InstanciaEstrategia instancia = findById(instanciaId);
            if (instancia != null) {
                instancia.setCapitalAsignadoActual(nuevoCapital);
                // Si el capital cae por debajo del umbral, se podría marcar como SIN_FONDOS aquí
                if (nuevoCapital <= 0.5) {
                    instancia.setEstado("SIN_FONDOS");
                }
                getEntityManager().merge(instancia);
            }
            commitTransaction();
        } catch (Exception e) {
            rollbackTransaction();
            System.err.println("❌ Error actualizando capital de instancia: " + e.getMessage());
        }
    }

    /**
     * Recupera todas las instancias activas asociadas a una wallet.
     */
    public List<InstanciaEstrategia> findActivasByWallet(String nombreWallet) {
        try {
            TypedQuery<InstanciaEstrategia> query = getEntityManager().createQuery(
                "SELECT i FROM InstanciaEstrategia i WHERE i.walletAsociada = :nombreWallet AND i.estado = 'ACTIVA'", 
                InstanciaEstrategia.class
            );
            query.setParameter("nombreWallet", nombreWallet);
            return query.getResultList();
        } catch (Exception e) {
            e.printStackTrace();
            return List.of();
        }
    }

    /**
     * Cambia el estado de la instancia (ej. a 'FINALIZADA' cuando el usuario detiene el bot)
     */
    public void cambiarEstado(Long instanciaId, String nuevoEstado) {
        try {
            beginTransaction();
            InstanciaEstrategia instancia = findById(instanciaId);
            if (instancia != null) {
                instancia.setEstado(nuevoEstado);
                getEntityManager().merge(instancia);
            }
            commitTransaction();
        } catch (Exception e) {
            rollbackTransaction();
        }
    }
}