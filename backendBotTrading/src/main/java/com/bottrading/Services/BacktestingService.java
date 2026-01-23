package com.bottrading.services;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.bottrading.beans.Vela;
import com.bottrading.utils.PathConfig;
import com.google.gson.Gson;

import org.springframework.stereotype.Service;

@Service
public class BacktestingService {

    private final Gson gson = new Gson();

    /**
     * Ejecuta el backtest de la estrategia enviando datos históricos a Python.
     */
    public String ejecutarBacktest(String rutaEstrategia, String timeframe, Map<String, List<Vela>> velasPorSimbolo)
            throws Exception {

        // Mapear velas de forma eficiente usando Streams si fuera necesario
        Map<String, List<Map<String, Object>>> velasMapeadas = new HashMap<>();

        velasPorSimbolo.forEach((symbol, velas) -> {
            List<Map<String, Object>> listaVelas = velas.stream().map(v -> {
                Map<String, Object> m = new HashMap<>();
                m.put("timestamp", v.getOpenTime());
                m.put("close", v.getClose() != null ? v.getClose().doubleValue() : null);
                m.put("open", v.getOpen() != null ? v.getOpen().doubleValue() : null);
                m.put("high", v.getHigh() != null ? v.getHigh().doubleValue() : null);
                m.put("low", v.getLow() != null ? v.getLow().doubleValue() : null);
                m.put("volume", v.getVolume() != null ? v.getVolume().doubleValue() : null);
                return m;
            }).collect(Collectors.toList());
            velasMapeadas.put(symbol, listaVelas);
        });

        // Crear payload
        Map<String, Object> payload = Map.of(
                "strategy_path", rutaEstrategia,
                "timeframe", timeframe,
                "velas", velasMapeadas,
                "capital", 1000.0,
                "risk_per_trade", 0.02);

        String jsonPayload = gson.toJson(payload);

        // Ejecutar Proceso
        ProcessBuilder pb = new ProcessBuilder("python", PathConfig.ENGINE_BACKTEST_PATH);
        pb.redirectErrorStream(false);
        Process process = pb.start();

        // 1. Enviar JSON al motor de Python (stdin)
        try (OutputStream os = process.getOutputStream()) {
            os.write(jsonPayload.getBytes(StandardCharsets.UTF_8));
            os.flush();
        }

        // 2. Leer resultado y errores
        String stdout = leerStream(process.getInputStream());
        String stderr = leerStream(process.getErrorStream());

        int exitCode = process.waitFor();

        if (exitCode != 0) {
            String errorMsg = stderr.isBlank() ? stdout : stderr;
            throw new Exception("Error en el motor de Backtest (Código " + exitCode + "):\n" + errorMsg);
        }

        return stdout;
    }

    private String leerStream(java.io.InputStream is) throws java.io.IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }
}