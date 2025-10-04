package com.bottrading.daos;

import java.math.BigDecimal;
import java.util.List;

import com.bottrading.Utils.WebSession;
import com.bottrading.beans.Vela;
import com.bottrading.beans.VelaDTO;

import jakarta.persistence.EntityManager;

public class VelaDAO extends BaseEntityDAO<Vela> {

    private static VelaDAO instance;

    private VelaDAO() {
        super(Vela.class);
    }

    public static VelaDAO getInstance() {
        if (instance == null) {
            instance = new VelaDAO();
        }
        return instance;
    }

    public Vela save(VelaDTO velaDTO) {
        Vela vela = new Vela();
        EntityManager em = WebSession.getInstance().getEntityManager();
        try {
            vela.setSymbol(velaDTO.symbol);
            vela.setInterval(velaDTO.time_interval);
            vela.setOpenTime(velaDTO.open_time);
            vela.setOpen(safeBigDecimal(velaDTO.open));
            vela.setHigh(safeBigDecimal(velaDTO.high));
            vela.setLow(safeBigDecimal(velaDTO.low));
            vela.setClose(safeBigDecimal(velaDTO.close));
            vela.setVolume(safeBigDecimal(velaDTO.volume));
            vela.setQuoteVolume(safeBigDecimal(velaDTO.quote_volume));
            vela.setTakerBaseVolume(safeBigDecimal(velaDTO.taker_base_volume));
            vela.setTakerQuoteVolume(safeBigDecimal(velaDTO.taker_quote_volume));
            vela.setCloseTime(velaDTO.close_time);
            em.getTransaction().begin();
            em.persist(vela);
            em.getTransaction().commit();
        } catch (Exception e) {
            em.getTransaction().rollback();
            e.printStackTrace();
        } finally {
            em.close();
        }
        return vela;
    }

    public Vela save(Vela vela){
        EntityManager em = WebSession.getInstance().getEntityManager();
        return super.save(em, vela);
    }

    private static BigDecimal safeBigDecimal(String val) {
        return (val != null && !val.isEmpty()) ? new BigDecimal(val) : BigDecimal.ZERO;
    }

    public List<Vela> findBySymbolAndInterval(String symbol, String interval) {
        EntityManager em = WebSession.getInstance().getEntityManager();
        List<Vela> velas = null;
        try {
            velas = em.createQuery("SELECT v FROM Vela v WHERE v.symbol = :symbol AND v.interval = :interval", Vela.class)
                    .setParameter("symbol", symbol)
                    .setParameter("interval", interval)
                    .getResultList();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            em.close();
        }
        return velas;
    }
}
