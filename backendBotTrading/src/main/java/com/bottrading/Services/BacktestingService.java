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

/**
 * Servicio encargado de la ejecución de simulaciones (Backtesting).
 * Actúa como puente entre los datos históricos de Java y el motor de cálculo en Python.
 */
@Service
public class BacktestingService {

    private final Gson gson = new Gson();

    /**
     * Ejecuta una simulación de estrategia enviando datos históricos formateados al motor de Python.
     *
     * @param rutaEstrategia Ruta absoluta del archivo .py de la estrategia a probar.
     * @param timeframe      Marco temporal de los datos (ej: "1m", "1h").
     * @param velasPorSimbolo Mapa que contiene la lista de velas históricas por cada par (Símbolo).
     * @return String conteniendo el JSON de resultados generado por Python.
     * @throws Exception Si ocurre un error de I/O o el proceso de Python termina con error.
     */
    public String ejecutarBacktest(String rutaEstrategia, String timeframe, Map<String, List<Vela>> velasPorSimbolo)
            throws Exception {

        // Transformación de objetos Vela a Mapas simples para serialización JSON
        Map<String, List<Map<String, Object>>> velasMapeadas = new HashMap<>();

        velasPorSimbolo.forEach((symbol, velas) -> {
            List<Map<String, Object>> listaVelas = velas.stream().map(v -> {
                Map<String, Object> m = new HashMap<>();
                m.put("timestamp", v.getOpenTime());
                m.put("open", v.getOpen() != null ? v.getOpen().doubleValue() : null);
                m.put("high", v.getHigh() != null ? v.getHigh().doubleValue() : null);
                m.put("low", v.getLow() != null ? v.getLow().doubleValue() : null);
                m.put("close", v.getClose() != null ? v.getClose().doubleValue() : null);
                m.put("volume", v.getVolume() != null ? v.getVolume().doubleValue() : null);
                return m;
            }).collect(Collectors.toList());
            velasMapeadas.put(symbol, listaVelas);
        });

        Map<String, Object> payload = Map.of(
                "strategy_path", rutaEstrategia,
                "timeframe", timeframe,
                "velas", velasMapeadas,
                "capital", 1000.0, // Capital base para la simulación
                "risk_per_trade", 0.02
        );

        String jsonPayload = gson.toJson(payload);

        ProcessBuilder pb = new ProcessBuilder("python", PathConfig.ENGINE_BACKTEST_PATH);
        pb.redirectErrorStream(false); // Mantenemos streams separados para diferenciar errores
        Process process = pb.start();

        // 1. Inyección de datos al motor (Stdin)
        try (OutputStream os = process.getOutputStream()) {
            os.write(jsonPayload.getBytes(StandardCharsets.UTF_8));
            os.flush();
        }

        // 2. Lectura de resultados (Stdout) y posibles errores (Stderr)
        String stdout = leerStream(process.getInputStream());
        String stderr = leerStream(process.getErrorStream());

        int exitCode = process.waitFor();

        if (exitCode != 0) {
            String errorMsg = stderr.isBlank() ? stdout : stderr;
            throw new Exception("Fallo en motor Backtest (Exit Code " + exitCode + "):\n" + errorMsg);
        }

        return stdout;
    }

    /**
     * Lee un flujo de entrada completo y lo convierte a String.
     */
    private String leerStream(java.io.InputStream is) throws java.io.IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }
}