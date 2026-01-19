package com.bottrading.controllers;

import com.bottrading.Services.FetchService;
import com.bottrading.Utils.WebSession;
import com.bottrading.beans.Vela;
import com.bottrading.beans.VelaDTO;
import com.bottrading.daos.VelaDAO;

import jakarta.persistence.EntityManager;

public class ControladorVela  {

    private VelaDAO velaDAO;

    public ControladorVela() {
        this.velaDAO = VelaDAO.getInstance();
    }

    public Vela obtenerVelaPorId(Long id) {
        EntityManager em = WebSession.getInstance().getEntityManager();
        Vela vela = null;
        try {
            vela = velaDAO.findById(em, id);
        } finally {
            em.close();
        }
        return vela;
    }

    public static void actualizarDatos(String symbol, String interval) {
        FetchService fetchService = new FetchService();
        fetchService.fetch(symbol, interval);
    }

    public void guardarVela(Vela vela) {
        velaDAO.save(vela);
    }

    public void guardarVela(VelaDTO vela) {
        velaDAO.save(vela);
    }

    public Long getUltimoTimeStamp(String symbol, String interval) {
        EntityManager em = WebSession.getInstance().getEntityManager();
        try {
            Long lastTimestamp = (Long) em.createQuery(
                    "SELECT MAX(v.openTime) FROM Vela v WHERE v.symbol = :symbol AND v.interval = :interval")
                    .setParameter("symbol", symbol)
                    .setParameter("interval", interval)
                    .getSingleResult();
            return lastTimestamp;
        } finally {
            em.close();
        }
    }

}
