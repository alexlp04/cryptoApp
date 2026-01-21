package com.bottrading.daos;

import com.bottrading.beans.IndicadorTecnico;
import com.bottrading.beans.Vela;

import java.util.List;

public class IndicadorDAO extends BaseEntityDAO<IndicadorTecnico> {

    private static IndicadorDAO instance;

    private IndicadorDAO() {
        super(IndicadorTecnico.class);
    }

    public static synchronized IndicadorDAO getInstance() {
        if (instance == null) {
            instance = new IndicadorDAO();
        }
        return instance;
    }

    // Buscar indicadores por vela
    public List<IndicadorTecnico> findByVela(Vela vela) {
        try {
            return getEntityManager()
                .createQuery(
                    "SELECT i FROM IndicadorTecnico i WHERE i.vela = :vela",
                    IndicadorTecnico.class)
                .setParameter("vela", vela)
                .getResultList();
        } catch (Exception e) {
            e.printStackTrace();
            return List.of();
        } finally {
            close();
        }
    }

    // Buscar indicadores por vela y tipo
    public List<IndicadorTecnico> findByVelaAndTipo(Vela vela, String tipo) {
        try {
            return getEntityManager()
                .createQuery(
                    "SELECT i FROM IndicadorTecnico i WHERE i.vela = :vela AND i.tipo = :tipo",
                    IndicadorTecnico.class)
                .setParameter("vela", vela)
                .setParameter("tipo", tipo)
                .getResultList();
        } catch (Exception e) {
            e.printStackTrace();
            return List.of();
        } finally {
            close();
        }
    }

    // Buscar indicador específico por vela, tipo y parámetros
    public IndicadorTecnico findByVelaTipoParametros(Vela vela, String tipo, String parametros) {
        try {
            List<IndicadorTecnico> resultados = getEntityManager()
                .createQuery(
                    "SELECT i FROM IndicadorTecnico i WHERE i.vela = :vela AND i.tipo = :tipo AND i.parametros = :parametros",
                    IndicadorTecnico.class)
                .setParameter("vela", vela)
                .setParameter("tipo", tipo)
                .setParameter("parametros", parametros)
                .getResultList();
            
            return resultados.isEmpty() ? null : resultados.get(0);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        } finally {
            close();
        }
    }

    // Verificar si existe un indicador
    public boolean exists(Vela vela, String tipo, String parametros) {
        try {
            Long count = (Long) getEntityManager()
                .createQuery(
                    "SELECT COUNT(i) FROM IndicadorTecnico i WHERE i.vela = :vela AND i.tipo = :tipo AND i.parametros = :parametros")
                .setParameter("vela", vela)
                .setParameter("tipo", tipo)
                .setParameter("parametros", parametros)
                .getSingleResult();
            
            return count > 0;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        } finally {
            close();
        }
    }

    // Eliminar indicadores de una vela
    public int deleteByVela(Vela vela) {
        try {
            beginTransaction();
            int deleted = getEntityManager()
                .createQuery("DELETE FROM IndicadorTecnico i WHERE i.vela = :vela")
                .setParameter("vela", vela)
                .executeUpdate();
            commitTransaction();
            return deleted;
        } catch (Exception e) {
            rollbackTransaction();
            e.printStackTrace();
            return 0;
        } finally {
            close();
        }
    }
}