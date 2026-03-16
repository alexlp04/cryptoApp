package com.bottrading.services;

import com.bottrading.beans.IndicadorTecnico;
import com.bottrading.beans.Vela;
import com.bottrading.config.ProcessExecutorConfig;
import com.bottrading.exceptions.StrategyExecutionException;
import com.bottrading.repositories.IndicadorRepository;
import com.bottrading.repositories.VelaRepository;
import com.bottrading.utils.PathConfig;
import com.bottrading.utils.PythonProcessSupport;
import com.google.gson.Gson;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Servicio encargado de orquestar el entrenamiento de modelos de Inteligencia
 * Artificial.
 * Fusiona los datos de mercado (Velas) con el Feature Engineering (Indicadores
 * Técnicos) y delega el entrenamiento al script de Python.
 *
 * Mejoras en Fase 3:
 * - Timeouts de 30s y retries automáticos (hasta 3 intentos)
 * - ExecutorService para gestión de procesos
 * - Destrucción forzada de procesos en caso de timeout
 * - Constructor injection (no @Autowired)
 */
@Slf4j
@Service
public class AITrainingService {

    private final MarketDataService marketDataService;
    private final VelaRepository velaRepo;
    private final IndicadorRepository indicadorRepo;
    private final ExecutorService executor = Executors.newFixedThreadPool(ProcessExecutorConfig.EXECUTOR_THREADS);

    public AITrainingService(MarketDataService marketDataService, VelaRepository velaRepo,
            IndicadorRepository indicadorRepo) {
        this.marketDataService = marketDataService;
        this.velaRepo = velaRepo;
        this.indicadorRepo = indicadorRepo;
    }

    // =========================================================================
    // LIFECYCLE MANAGEMENT
    // =========================================================================

    @PreDestroy
    public void shutdown() {
        log.info("Iniciando shutdown graceful de AITrainingService...");
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warn("ExecutorService no terminó en 10s, forzando shutdown");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupción durante shutdown: {}", e.getMessage());
            executor.shutdownNow();
        }
    }

    /**
     * Orquesta el proceso de preparación de datos y entrenamiento con retries automáticos.
     */
    public String entrenarModelo(String nombreModelo, String timeframe, String symbol, int dias, Map<String, Object> hyperparams) {
        for (int intento = 1; intento <= ProcessExecutorConfig.MAX_RETRIES; intento++) {
            try {
                log.info("Intento {} de {} para entrenar modelo '{}'", intento, ProcessExecutorConfig.MAX_RETRIES,
                        nombreModelo);
                return ejecutarEntrenamiento(nombreModelo, timeframe, symbol, dias, hyperparams);

            } catch (StrategyExecutionException e) {
                if (intento == ProcessExecutorConfig.MAX_RETRIES) {
                    log.error("Entrenamiento falló después de {} intentos: {}", ProcessExecutorConfig.MAX_RETRIES,
                            e.getMessage());
                    throw e;
                }
                log.warn("Intento {} falló, reintentando en {}ms...", intento, ProcessExecutorConfig.RETRY_DELAY_MS);
                try {
                    Thread.sleep(ProcessExecutorConfig.RETRY_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new StrategyExecutionException("Entrenamiento interrumpido durante reintento", ie);
                }
            }
        }

        throw new StrategyExecutionException("Entrenamiento falló tras " + ProcessExecutorConfig.MAX_RETRIES + " intentos");
    }

    private String ejecutarEntrenamiento(String nombreModelo, String timeframe, String symbol, int dias, Map<String, Object> hyperparams) throws StrategyExecutionException {
        try {
            long now = System.currentTimeMillis();

            // 1. Asegurar que los datos y los indicadores están actualizados
            marketDataService.prepararDatosParaEntrenamiento(symbol, timeframe, dias, now);

            log.info("Extrayendo dataset (Velas + Indicadores) de la base de datos...");

            // Calcular timestamp objetivo
            long targetTimestamp = now - (dias * 24L * 60L * 60L * 1000L);

            // 2. Extraer Velas Históricas
            List<Vela> velas = velaRepo.findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(
                    symbol, timeframe, targetTimestamp);

            if (velas.isEmpty()) {
                return "Error: No hay datos suficientes de " + symbol + " para entrenar.";
            }

            log.info("Extraídas {} velas. Obteniendo indicadores en bloque (Alta velocidad)...", velas.size());

            List<IndicadorTecnico> todosLosIndicadores = indicadorRepo.findByVelaIn(velas);
            Map<Long, List<IndicadorTecnico>> indicadoresPorVela = todosLosIndicadores.stream()
                    .collect(Collectors.groupingBy(ind -> ind.getVela().getId()));

            log.info("Fusionando datos en memoria RAM...");

            // 3. Montar el Dataset fusionando Velas e Indicadores
            List<Map<String, Object>> dataset = new ArrayList<>();
            for (Vela v : velas) {
                Map<String, Object> row = new HashMap<>();
                row.put("timestamp", v.getOpenTime());
                row.put("close", v.getClose());
                row.put("volume", v.getVolume());

                // Recuperar indicadores desde el mapa en memoria
                List<IndicadorTecnico> indicadoresVela = indicadoresPorVela.getOrDefault(v.getId(), new ArrayList<>());
                for (IndicadorTecnico ind : indicadoresVela) {
                    row.put(ind.getTipo(), ind.getValor());
                }

                dataset.add(row);
            }

            log.info("--- DATASET LISTO --- {} registros", dataset.size());

            // 4. Construir el JSON para enviar a Python
            Map<String, Object> payload = new HashMap<>();
            payload.put("model_type", nombreModelo);
            payload.put("symbol", symbol);
            payload.put("timeframe", timeframe);
            payload.put("dataset", dataset);
            payload.put("hyperparameters", hyperparams);

            String jsonPayload = new Gson().toJson(payload);

            log.info("Enviando {} registros al motor de IA (Python)...", dataset.size());

            // Limpieza de memoria RAM
            velas.clear();
            todosLosIndicadores.clear();
            indicadoresPorVela.clear();
            dataset.clear();

            // 5. Ejecutar script Python con timeouts
            return invocarMotorPythonConTimeouts(jsonPayload);

        } catch (Exception e) {
            log.error("Fallo durante entrenamiento: {}", e.getMessage(), e);
            throw new StrategyExecutionException("Error crítico entrenando modelo: " + e.getMessage(), e);
        }
    }

    /**
     * Invoca el motor Python con timeout de 30s y destrucción forzada en caso de error.
     */
    private String invocarMotorPythonConTimeouts(String jsonPayload) throws StrategyExecutionException {
        Process process = null;
        try {
            long startTime = System.currentTimeMillis();
            log.debug("Iniciando entrenamiento IA con payload de {} bytes", jsonPayload.length());

            process = PythonProcessSupport.startPythonScript(PathConfig.ENGINE_TRAIN_PATH, false);

            escribirPayloadEntrenamiento(process, jsonPayload);

            // Esperar con timeout
            boolean finished = PythonProcessSupport.waitFor(process, ProcessExecutorConfig.TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (!finished) {
                log.error("Entrenamiento IA timeout después de {}s, destruyendo proceso", 
                        ProcessExecutorConfig.TIMEOUT_SECONDS);
                destroyProcessForcibly(process);
                throw new StrategyExecutionException(
                        "Entrenamiento IA excedió timeout de " + ProcessExecutorConfig.TIMEOUT_SECONDS + "s");
            }

            long duration = System.currentTimeMillis() - startTime;

            // Leer salida
            String stdout = leerStream(process.getInputStream());
            String stderr = leerStream(process.getErrorStream());
            int exitCode = process.exitValue();

            log.info("Entrenamiento IA completado en {} ms (exit code: {})", duration, exitCode);

            if (exitCode != 0) {
                String errorMsg = !stderr.isBlank() ? stderr : stdout;
                log.error("Motor IA falló con código {}: {}", exitCode, errorMsg);
                throw new StrategyExecutionException("Entrenamiento IA falló (Exit Code " + exitCode + "):\n" + errorMsg);
            }

            return stdout;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Proceso IA interrumpido: {}", e.getMessage());
            if (process != null) {
                destroyProcessForcibly(process);
            }
            throw new StrategyExecutionException("Entrenamiento IA interrumpido", e);
        } catch (StrategyExecutionException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error inesperado en entrenamiento IA: {}", e.getMessage(), e);
            if (process != null) {
                destroyProcessForcibly(process);
            }
            throw new StrategyExecutionException("Error inesperado: " + e.getMessage(), e);
        }
    }

    /**
     * Destruye un proceso forzosamente y registra el evento.
     */
    private void destroyProcessForcibly(Process process) {
        if (process != null && process.isAlive()) {
            log.warn("Destruyendo proceso Python forzadamente");
            PythonProcessSupport.destroyForcibly(process, 2, TimeUnit.SECONDS);
        }
    }

    private void escribirPayloadEntrenamiento(Process process, String payload) {
        try (OutputStream os = process.getOutputStream()) {
            PythonProcessSupport.writeUtf8(os, payload);
        } catch (IOException e) {
            log.error("Error escribiendo payload al motor IA: {}", e.getMessage());
            destroyProcessForcibly(process);
            throw new StrategyExecutionException("No se pudo escribir datos al motor IA", e);
        }
    }

    private String leerStream(java.io.InputStream is) throws java.io.IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }
}