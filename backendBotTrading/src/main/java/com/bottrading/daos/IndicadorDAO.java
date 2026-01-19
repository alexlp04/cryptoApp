package com.bottrading.daos;

import com.bottrading.Utils.WebSession;
import com.bottrading.beans.IndicadorTecnico;

import jakarta.persistence.EntityManager;

public class IndicadorDAO extends BaseEntityDAO<IndicadorTecnico> {

    private static IndicadorDAO instance;

    private IndicadorDAO() {
        super(IndicadorTecnico.class);
    }

    public static IndicadorDAO getInstance() {
        if (instance == null) {
            instance = new IndicadorDAO();
        }
        return instance;
    }

    public IndicadorTecnico save(IndicadorTecnico indicador) {
        EntityManager em = WebSession.getInstance().getEntityManager();
        return super.save(em, indicador);
        
    }
}
