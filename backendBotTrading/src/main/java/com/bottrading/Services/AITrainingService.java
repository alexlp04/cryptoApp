package com.bottrading.services;

import com.bottrading.beans.IndicadorTecnico;
import com.bottrading.beans.Vela;
import com.bottrading.repositories.IndicadorRepository;
import com.bottrading.repositories.VelaRepository;
import com.bottrading.utils.PathConfig;
import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Servicio encargado de orquestar el entrenamiento de modelos de Inteligencia Artificial.
 * Fusiona los datos de mercado (Velas) con el Feature Engineering (Indicadores Técnicos)
 * y delega el entrenamiento al script de Python.
 */
@Slf4j
@Service
public class AITrainingService {

    private final MarketDataService marketDataService;
    private final VelaRepository velaRepo;
    private final IndicadorRepository indicadorRepo;
    private final Gson gson = new Gson();

    @Autowired
    public AITrainingService(MarketDataService marketDataService, VelaRepository velaRepo, IndicadorRepository indicadorRepo) {
        this.marketDataService = marketDataService;
        this.velaRepo = velaRepo;
        this.indicadorRepo = indicadorRepo;
    }

    /**
     * Orquesta el proceso de preparación de datos y entrenamiento.
     */
    @Transactional
    public String entrenarModelo(String nombreModelo, String timeframe, String symbol) {
        try {
            // 1. Asegurar que los datos y los indicadores están actualizados
            marketDataService.prepararDatosParaEntrenamiento(symbol, timeframe);

            log.info("Extrayendo dataset (Velas + Indicadores) de la base de datos...");
            
            // 2. Extraer Velas Históricas
            List<Vela> velas = velaRepo.findBySymbolAndIntervalOrderByOpenTimeAsc(symbol, timeframe);
            if (velas.isEmpty()) {
                return "Error: No hay datos suficientes de " + symbol + " para entrenar.";
            }

            // 3. Montar el Dataset fusionando Velas e Indicadores
            List<Map<String, Object>> dataset = new ArrayList<>();
            for (Vela v : velas) {
                Map<String, Object> row = new HashMap<>();
                row.put("timestamp", v.getOpenTime());
                row.put("close", v.getClose());
                row.put("volume", v.getVolume());

                // Recuperar indicadores asociados a esta vela (RSI, MACD, etc.)
                List<IndicadorTecnico> indicadores = indicadorRepo.findByVela(v);
                for (IndicadorTecnico ind : indicadores) {
                    // Crea columnas dinámicas: Ej -> "RSI": 45.2
                    row.put(ind.getTipo(), ind.getValor());
                }
                dataset.add(row);
            }

            // 4. Construir el JSON para enviar a Python
            Map<String, Object> payload = new HashMap<>();
            payload.put("model_type", nombreModelo); // ej: "random_forest"
            payload.put("symbol", symbol);
            payload.put("timeframe", timeframe);
            payload.put("dataset", dataset);

            String jsonPayload = gson.toJson(payload);

            log.info("Enviando {} registros al motor de IA (Python)...", dataset.size());
            
            // 5. Ejecutar script Python
            return invocarMotorPython(jsonPayload);

        } catch (Exception e) {
            log.error("Fallo durante el entrenamiento: {}", e.getMessage(), e);
            return "Error crítico entrenando modelo: " + e.getMessage();
        }
    }

    private String invocarMotorPython(String jsonPayload) throws Exception {
        // Llama al script definido en PathConfig
        ProcessBuilder pb = new ProcessBuilder("python", PathConfig.ENGINE_TRAIN_PATH);
        pb.redirectErrorStream(false);
        Process process = pb.start();

        // Enviar el JSON enorme por la entrada estándar (Stdin)
        try (OutputStream os = process.getOutputStream()) {
            os.write(jsonPayload.getBytes(StandardCharsets.UTF_8));
            os.flush();
        }

        // Leer la respuesta (Métricas de la IA)
        String stdout = leerStream(process.getInputStream());
        String stderr = leerStream(process.getErrorStream());

        int exitCode = process.waitFor();

        if (exitCode != 0) {
            String errorMsg = stderr.isBlank() ? stdout : stderr;
            throw new Exception("Fallo en motor de IA (Exit Code " + exitCode + "):\n" + errorMsg);
        }

        return stdout;
    }

    private String leerStream(java.io.InputStream is) throws java.io.IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }
}