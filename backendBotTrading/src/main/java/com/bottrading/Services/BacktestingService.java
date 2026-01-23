package com.bottrading.Services;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.bottrading.Utils.PathConfig;
import com.bottrading.beans.Vela;
import com.google.gson.Gson;

public class BacktestingService {

    /**
     * Ejecuta el backtest de la estrategia pasando todo en JSON por stdin.
     * 
     * @param rutaEstrategia  ruta del fichero .py con la estrategia
     * @param timeframe       timeframe de las velas
     * @param velasPorSimbolo velas agrupadas por símbolo
     * @return JSON con los trades
     * @throws Exception si falla la ejecución del script Python
     */
    public String ejecutarBacktest(String rutaEstrategia, String timeframe, HashMap<String, List<Vela>> velasPorSimbolo)
            throws Exception {

        Gson gson = new Gson();

        // Mapear velas a formato JSON que Python espera
        Map<String, List<Map<String, Object>>> velasMapeadas = new HashMap<>();
        for (Map.Entry<String, List<Vela>> entry : velasPorSimbolo.entrySet()) {
            String symbol = entry.getKey();
            List<Vela> velas = entry.getValue();
            List<Map<String, Object>> listaVelas = new ArrayList<>();

            for (Vela v : velas) {
                Map<String, Object> m = new HashMap<>();
                m.put("timestamp", v.getOpenTime()); // Python espera 'timestamp'
                m.put("close", v.getClose() != null ? v.getClose().doubleValue() : null);
                m.put("open", v.getOpen() != null ? v.getOpen().doubleValue() : null);
                m.put("high", v.getHigh() != null ? v.getHigh().doubleValue() : null);
                m.put("low", v.getLow() != null ? v.getLow().doubleValue() : null);
                m.put("volume", v.getVolume() != null ? v.getVolume().doubleValue() : null);
                listaVelas.add(m);
            }
            velasMapeadas.put(symbol, listaVelas);
        }

        // Creamos payload JSON
        Map<String, Object> payload = new HashMap<>();
        payload.put("strategy_path", rutaEstrategia);
        payload.put("timeframe", timeframe);
        payload.put("velas", velasMapeadas);
        payload.put("capital", 1000.0); // Valores por defecto
        payload.put("risk_per_trade", 0.02);

        String jsonPayload = gson.toJson(payload);

        ProcessBuilder pb = new ProcessBuilder("python", PathConfig.ENGINE_BACKTEST_PATH);
        // IMPORTANTE: NO redirigir ErrorStream para poder leerlos por separado
        pb.redirectErrorStream(false);
        Process process = pb.start();

        // 1. Enviar JSON
        try (OutputStream os = process.getOutputStream()) {
            os.write(jsonPayload.getBytes(StandardCharsets.UTF_8));
            os.flush();
        }

        // 2. Leer salida estándar (Éxito)
        StringBuilder stdout = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                stdout.append(line);
            }
        }

        // 3. Leer errores (Fallo)
        StringBuilder stderr = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                stderr.append(line).append("\n");
            }
        }

        int exitCode = process.waitFor();

        if (exitCode != 0) {
            // Aquí personalizamos el mensaje de error que pidió el usuario
            String errorFinal = stderr.length() > 0 ? stderr.toString() : stdout.toString();
            throw new Exception("Error por el engine:\n" + errorFinal);
        }

        return stdout.toString();
    }
}
