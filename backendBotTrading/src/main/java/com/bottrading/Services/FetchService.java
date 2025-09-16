package com.bottrading.Services;

import com.bottrading.Utils.WebSession;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import jakarta.persistence.EntityManager;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.List;

import com.bottrading.beans.Vela;
import com.bottrading.beans.VelaDTO;

public class FetchService {

    public static void fetch(String symbol, String interval, int limit) {
        EntityManager em = WebSession.getInstance().getEntityManager();
        System.out.println("Iniciando fetch para " + symbol);
        try {
            // Buscar el último registro en BD
            Long lastTimestamp = (Long) em.createQuery(
                    "SELECT MAX(v.openTime) FROM Vela v WHERE v.symbol = :symbol AND v.interval = :interval")
                    .setParameter("symbol", symbol)
                    .setParameter("interval", interval)
                    .getSingleResult();

            if (lastTimestamp == null) {
                System.out.println("No hay datos en la BD. Descargando todo...");
                callPythonAndSave(symbol, interval, limit, em);
            } else {
                // comprobar si hay nuevas velas
                long now = System.currentTimeMillis();
                if (now - lastTimestamp > getIntervalMillis(interval)) {
                    System.out.println("Datos desactualizados. Descargando desde " + lastTimestamp);
                    callPythonAndSave(symbol, interval, limit, em, lastTimestamp);
                } else {
                    System.out.println("Los datos ya están al día.");
                }
            }
        } finally {
            em.close();
        }
    }

    private static void callPythonAndSave(String symbol, String interval, int limit, EntityManager em) {
        callPythonAndSave(symbol, interval, limit, em, null);
    }

    private static void callPythonAndSave(String symbol, String interval, int limit, EntityManager em,
            Long fromTimestamp) {
        try {
            ProcessBuilder pb;
            if (fromTimestamp == null) {
                pb = new ProcessBuilder("python", "python-scripts/fetcher.py", symbol, interval, String.valueOf(limit));
            } else {
                pb = new ProcessBuilder("python", "python-scripts/fetcher.py", symbol, interval, String.valueOf(fromTimestamp));
            }
            pb.redirectErrorStream(true);
            Process process = pb.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            Gson gson = new Gson();
            Type listType = new TypeToken<List<VelaDTO>>() {
            }.getType();

            while ((line = reader.readLine()) != null) {
                // Cada línea es un batch de velas en JSON
                    if (line.trim().isEmpty()) continue;

                List<VelaDTO> dtoList = gson.fromJson(line, listType);

                List<Vela> velas = dtoList.stream().map(dto -> {
                    Vela v = new Vela();
                    v.setSymbol(dto.symbol);
                    v.setInterval(dto.time_interval);
                    v.setOpenTime(dto.open_time);
                    v.setOpen(new BigDecimal(dto.open));
                    v.setHigh(new BigDecimal(dto.high));
                    v.setLow(new BigDecimal(dto.low));
                    v.setClose(new BigDecimal(dto.close));
                    v.setVolume(new BigDecimal(dto.volume));
                    v.setCloseTime(dto.close_time);
                    v.setQuoteVolume(new BigDecimal(dto.quote_volume));
                    v.setTrades(dto.trades);
                    v.setTakerBaseVolume(new BigDecimal(dto.taker_base_volume));
                    v.setTakerQuoteVolume(new BigDecimal(dto.taker_quote_volume));
                    return v;
                }).toList();

                // Guardar batch en BD
                em.getTransaction().begin();
                for (Vela v : velas) {
                    em.persist(v);
                }
                em.getTransaction().commit();

                System.out.println("Guardadas " + velas.size() + " velas en la BD (batch incremental).");
            }

            process.waitFor();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static long getIntervalMillis(String interval) {
        return switch (interval) {
            case "1m" -> 60_000L;
            case "5m" -> 5 * 60_000L;
            case "15m" -> 15 * 60_000L;
            case "1h" -> 60 * 60_000L;
            case "4h" -> 4 * 60 * 60_000L;
            case "1d" -> 24 * 60 * 60_000L;
            default -> 60_000L;
        };
    }
}
