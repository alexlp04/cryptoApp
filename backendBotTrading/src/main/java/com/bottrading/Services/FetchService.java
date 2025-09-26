package com.bottrading.Services;

import com.bottrading.Utils.WebSession;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import jakarta.persistence.EntityManager;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.util.List;
import com.bottrading.beans.VelaDTO;
import com.bottrading.controllers.ControladorVela;

public class FetchService {

    public static void fetch(String symbol, String interval) {
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
                callPythonAndSave(symbol, interval, em);
            } else {
                // comprobar si hay nuevas velas
                long now = System.currentTimeMillis();
                if (now - lastTimestamp > getIntervalMillis(interval)) {
                    System.out.println("Datos desactualizados. Descargando desde " + lastTimestamp);
                    callPythonAndSave(symbol, interval, em, lastTimestamp);
                } else {
                    System.out.println("Los datos ya están al día.");
                }
            }
        } finally {
            em.close();
        }
    }

    private static void callPythonAndSave(String symbol, String interval, EntityManager em) {
        callPythonAndSave(symbol, interval, em, 0L);
    }

    private static void callPythonAndSave(String symbol, String interval, EntityManager em,
            Long fromTimestamp) {
        try {
            ProcessBuilder pb;
            if (fromTimestamp == null) {
                pb = new ProcessBuilder("python3",
                        "C:/Users/Alejandro/Desktop/Informatica/cryptoApp/python-scripts/fetcher.py", symbol, interval,
                        null);
            } else {
                pb = new ProcessBuilder("python3",
                        "C:/Users/Alejandro/Desktop/Informatica/cryptoApp/python-scripts/fetcher.py", symbol, interval,
                        String.valueOf(fromTimestamp));
            }

            pb.redirectErrorStream(true);
            Process process = pb.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            guardarInputStream(reader);

        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    public static void guardarInputStream(BufferedReader reader) throws Exception {
        StringBuilder output = new StringBuilder();
        String line;

        System.out.println("Leyendo datos de la salida del proceso Python...");

        while ((line = reader.readLine()) != null) {
            output.append(line);
        }

        // Ahora output contiene todo el JSON
        String jsonOutput = output.toString();
        System.out.println("DEBUG JSON recibido: " + jsonOutput);

        // Parsear directamente con Gson
        Gson gson = new Gson();
        Type listType = new TypeToken<List<VelaDTO>>(){}.getType();
        List<VelaDTO> dtoList = gson.fromJson(jsonOutput, listType);

        // Mapear a tus entidades
        ControladorVela controladorVela = new ControladorVela();
        dtoList.stream().forEach(velaDTO -> controladorVela.guardarVela(velaDTO));
        System.out.println("Guardadas " + dtoList.size() + " velas en la BD (batch incremental).");
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
