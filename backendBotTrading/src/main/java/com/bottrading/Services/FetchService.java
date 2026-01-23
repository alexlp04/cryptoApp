package com.bottrading.Services;

import com.bottrading.Utils.ConsoleLoader;
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

    private ControladorVela controladorVela = new ControladorVela();
    private static final String FETCHER_ENGINE_PATH = "D:\\Users\\Alejandro\\Documents\\Informatica\\cryptoApp\\python-scripts\\fetcher.py";

    public FetchService() {
    }

    public void fetch(String symbol, String interval) {
        EntityManager em = WebSession.getInstance().getEntityManager();
        System.out.println("Iniciando fetch para " + symbol);
        try {
            // Buscar el último registro en BD
            Long lastTimestamp = controladorVela.getUltimoTimeStamp(symbol, interval);

            if (lastTimestamp == null) {
                System.out.println("No hay datos en la BD. Descargando todo...");
                this.callPythonAndSave(symbol, interval, em);
            } else {
                // comprobar si hay nuevas velas
                long now = System.currentTimeMillis();
                if (now - lastTimestamp > getIntervalMillis(interval)) {
                    System.out.println("Datos desactualizados. Descargando desde " + lastTimestamp);
                    this.callPythonAndSave(symbol, interval, em, lastTimestamp);
                } else {
                    System.out.println("Los datos ya están al día.");
                }
            }
        } finally {
            em.close();
        }
    }

    private void callPythonAndSave(String symbol, String interval, EntityManager em) {
        ConsoleLoader loader = ConsoleLoader.getInstance();
        loader.startDots();
        this.callPythonAndSave(symbol, interval, em, null);
        loader.stop();
    }

    private void callPythonAndSave(String symbol, String interval, EntityManager em,
            Long fromTimestamp) {
        ConsoleLoader loader = ConsoleLoader.getInstance();

        try {
            ProcessBuilder pb;
            if (fromTimestamp == null) {
                pb = new ProcessBuilder("python3", FETCHER_ENGINE_PATH, symbol, interval);
            } else {
                pb = new ProcessBuilder("python3", FETCHER_ENGINE_PATH, symbol, interval,
                        String.valueOf(fromTimestamp));
            }

            pb.redirectErrorStream(true);
            loader.startDots();
            Process process = pb.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;

            while ((line = reader.readLine()) != null) {
                output.append(line);
            }

            loader.stop();

            // Ahora output contiene todo el JSON
            String jsonOutput = output.toString();

            // Parsear directamente con Gson
            Gson gson = new Gson();
            Type listType = new TypeToken<List<VelaDTO>>() {
            }.getType();
            List<VelaDTO> dtoList = gson.fromJson(jsonOutput, listType);
            ConsoleLoader.getInstance().startDots();
            // Mapear a tus entidades
            dtoList.stream().forEach(velaDTO -> controladorVela.guardarVela(velaDTO));
            if (dtoList.isEmpty())
                System.out.println("No existe ese símbolo dentro del exchange o el intervalo no esta bien escrito.");
            else {
                ConsoleLoader.getInstance().startDots();
                System.out.println("Guardadas " + dtoList.size() + " velas en la BD (batch incremental).");
            }
            
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
