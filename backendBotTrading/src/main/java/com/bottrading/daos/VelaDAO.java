package com.bottrading.daos;

import java.math.BigDecimal;
import java.util.List;

import com.bottrading.beans.Vela;
import com.bottrading.beans.VelaDTO;

public class VelaDAO extends BaseEntityDAO<Vela> {

    private static VelaDAO instance;

    private VelaDAO() {
        super(Vela.class);
    }

    public static synchronized VelaDAO getInstance() {
        if (instance == null) {
            instance = new VelaDAO();
        }
        return instance;
    }

    // Guardar desde VelaDTO
    public Vela save(VelaDTO velaDTO) {
        Vela vela = new Vela();
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
        
        try {
            return save(vela); // Usa el método heredado
        } finally {
            close();
        }
    }

    // Buscar velas por símbolo e intervalo
    public List<Vela> findBySymbolAndInterval(String symbol, String interval) {
        try {
            return getEntityManager()
                .createQuery(
                    "SELECT v FROM Vela v WHERE v.symbol = :symbol AND v.interval = :interval ORDER BY v.openTime",
                    Vela.class)
                .setParameter("symbol", symbol)
                .setParameter("interval", interval)
                .getResultList();
        } catch (Exception e) {
            e.printStackTrace();
            return List.of(); // Lista vacía en caso de error
        } finally {
            close();
        }
    }

    // Obtener última vela
    public Vela findLastVela(String symbol, String interval) {
        try {
            List<Vela> velas = getEntityManager()
                .createQuery(
                    "SELECT v FROM Vela v WHERE v.symbol = :symbol AND v.interval = :interval ORDER BY v.openTime DESC",
                    Vela.class)
                .setParameter("symbol", symbol)
                .setParameter("interval", interval)
                .setMaxResults(1)
                .getResultList();
            
            return velas.isEmpty() ? null : velas.get(0);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        } finally {
            close();
        }
    }

    // Obtener último timestamp
    public Long getLastTimestamp(String symbol, String interval) {
        try {
            Long timestamp = (Long) getEntityManager()
                .createQuery(
                    "SELECT MAX(v.openTime) FROM Vela v WHERE v.symbol = :symbol AND v.interval = :interval")
                .setParameter("symbol", symbol)
                .setParameter("interval", interval)
                .getSingleResult();
            
            return timestamp != null ? timestamp : 0L;
        } catch (Exception e) {
            e.printStackTrace();
            return 0L;
        } finally {
            close();
        }
    }

    // Verificar si existe una vela
    public boolean exists(String symbol, String interval, Long openTime) {
        try {
            Long count = (Long) getEntityManager()
                .createQuery(
                    "SELECT COUNT(v) FROM Vela v WHERE v.symbol = :symbol AND v.interval = :interval AND v.openTime = :openTime")
                .setParameter("symbol", symbol)
                .setParameter("interval", interval)
                .setParameter("openTime", openTime)
                .getSingleResult();
            
            return count > 0;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        } finally {
            close();
        }
    }

    // Método auxiliar
    private static BigDecimal safeBigDecimal(String val) {
        return (val != null && !val.isEmpty()) ? new BigDecimal(val) : BigDecimal.ZERO;
    }
}