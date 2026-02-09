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
 * Actúa como puente entre los datos históricos de Java y el motor de cálculo en
 * Python.
 */
@Service
public class BacktestingService {

    private final Gson gson = new Gson();

    /**
     * Método principal: Orquestador.
     * Su complejidad cognitiva ahora es mínima porque solo llama a otros métodos.
     */
    public String ejecutarBacktest(String rutaEstrategia, String timeframe, Map<String, List<Vela>> velasPorSimbolo)
            throws Exception {
        // 1. Preparar datos
        Map<String, List<Map<String, Object>>> velasMapeadas = transformarVelasParaPython(velasPorSimbolo);

        // 2. Construir Payload
        String jsonPayload = construirPayload(rutaEstrategia, timeframe, velasMapeadas);

        // 3. Ejecutar proceso externo
        return invocarMotorPython(jsonPayload);
    }

    // =========================================================================
    // MÉTODOS AUXILIARES (Refactorización para reducir complejidad)
    // =========================================================================

    private Map<String, List<Map<String, Object>>> transformarVelasParaPython(Map<String, List<Vela>> velasPorSimbolo) {
        Map<String, List<Map<String, Object>>> resultado = new HashMap<>();

        velasPorSimbolo.forEach((symbol, listaVelas) -> {
            List<Map<String, Object>> listaTransformada = listaVelas.stream()
                    .map(this::convertirVelaAMapa) // Delegamos la conversión de 1 vela
                    .collect(Collectors.toList());
            resultado.put(symbol, listaTransformada);
        });

        return resultado;
    }

    private Map<String, Object> convertirVelaAMapa(Vela v) {
        Map<String, Object> m = new HashMap<>();
        m.put("timestamp", v.getOpenTime());
        // El uso de Optional o ternarios simples reduce la anidación visual
        m.put("open", v.getOpen() != null ? v.getOpen().doubleValue() : null);
        m.put("high", v.getHigh() != null ? v.getHigh().doubleValue() : null);
        m.put("low", v.getLow() != null ? v.getLow().doubleValue() : null);
        m.put("close", v.getClose() != null ? v.getClose().doubleValue() : null);
        m.put("volume", v.getVolume() != null ? v.getVolume().doubleValue() : null);
        return m;
    }

    private String construirPayload(String ruta, String tf, Map<String, List<Map<String, Object>>> velas) {
        Map<String, Object> payload = Map.of(
                "strategy_path", ruta,
                "timeframe", tf,
                "velas", velas,
                "capital", 1000.0,
                "risk_per_trade", 0.02);
        return gson.toJson(payload);
    }

    private String invocarMotorPython(String jsonPayload) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("python", PathConfig.ENGINE_BACKTEST_PATH);
        pb.redirectErrorStream(false);
        Process process = pb.start();

        // Escritura (Input)
        try (OutputStream os = process.getOutputStream()) {
            os.write(jsonPayload.getBytes(StandardCharsets.UTF_8));
            os.flush();
        }

        // Lectura (Output)
        // helper
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