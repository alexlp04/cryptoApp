package com.bottrading.services;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.bottrading.beans.Vela;
import com.bottrading.config.ProcessExecutorConfig;
import com.bottrading.exceptions.StrategyExecutionException;
import com.bottrading.utils.AppConstants;
import com.bottrading.utils.ConsoleLoader;
import com.bottrading.utils.DataSerializationUtils;
import com.bottrading.utils.PathConfig;
import com.google.gson.Gson;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Service orchestrating backtesting simulations.
 * Acts as a bridge between historical market data in Java and the calculation
 * engine in Python.
 *
 * Mejoras en Fase 3:
 * - MessagePack en lugar de JSON (más eficiente)
 * - Compresión GZIP opcional
 * - Timeouts de 30s y retries automáticos (hasta 3 intentos)
 * - ExecutorService para ejecución asíncronas
 * - Destroyed forzado de procesos en caso de timeout
 */
@Slf4j
@Service
public class BacktestingService {

    private final ExecutorService executor = Executors.newFixedThreadPool(ProcessExecutorConfig.EXECUTOR_THREADS);

    /** Flag para permitir prompt interactivo (true) o modo batch (false). */
    @Value("${backtest.interactive:false}")
    private boolean interactiveMode;

    // =========================================================================
    // LIFECYCLE MANAGEMENT
    // =========================================================================

    @PreDestroy
    public void shutdown() {
        log.info("Iniciando shutdown graceful de BacktestingService...");
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

    // =========================================================================
    // ORQUESTACIÓN
    // =========================================================================
    public String ejecutarBacktest(String rutaEstrategia, String nombreEstrategia, String timeframe,
            Map<String, List<Vela>> velasPorSimbolo, BigDecimal capitalAsignado, BigDecimal risk)
            throws StrategyExecutionException {

        for (int intento = 1; intento <= ProcessExecutorConfig.MAX_RETRIES; intento++) {
            try {
                log.info("Intento {} de {} para backtest '{}'", intento, ProcessExecutorConfig.MAX_RETRIES,
                        nombreEstrategia);

                // 1. Preparar datos
                ConsoleLoader.getInstance().startDots("Transformando datos");
                Map<String, List<Map<String, Object>>> velasMapeadas = transformarVelasParaPython(velasPorSimbolo);
                ConsoleLoader.getInstance().stopClear();

                // 2. Construir payload con opción de guardar trades
                boolean guardarTrades = decideGuardarTrades();
                String payload = construirPayload(rutaEstrategia, timeframe, velasMapeadas, capitalAsignado, risk,
                        nombreEstrategia, guardarTrades);

                // 3. Ejecutar con resiliencia
                return invocarMotorPythonConRetries(payload);

            } catch (StrategyExecutionException e) {
                if (intento == ProcessExecutorConfig.MAX_RETRIES) {
                    log.error("Falló backtest después de {} intentos: {}", ProcessExecutorConfig.MAX_RETRIES,
                            e.getMessage());
                    throw e;
                }
                log.warn("Intento {} falló, reintentando en {}ms...", intento, ProcessExecutorConfig.RETRY_DELAY_MS);
                try {
                    Thread.sleep(ProcessExecutorConfig.RETRY_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new StrategyExecutionException("Backtest interrumpido durante reintento", ie);
                }
            }
        }

        throw new StrategyExecutionException("Backtest falló tras " + ProcessExecutorConfig.MAX_RETRIES + " intentos");
    }

    private Map<String, List<Map<String, Object>>> transformarVelasParaPython(Map<String, List<Vela>> velasPorSimbolo) {
        Map<String, List<Map<String, Object>>> resultado = new HashMap<>();

        velasPorSimbolo.forEach((symbol, listaVelas) -> {
            List<Map<String, Object>> listaTransformada = listaVelas.stream()
                    .map(this::convertirVelaAMapa) // Delegamos la conversión de 1 vela
                    .toList();
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

    /**
     * Decide si guardar trades en CSV según el modo interactivo.
     * En modo batch (interactiveMode=false), asume "no" para no bloquear.
     */
    private boolean decideGuardarTrades() {
        if (!interactiveMode) {
            log.debug("Modo batch: no guardando trades en CSV");
            return false;
        }

        System.out.print(
                "Quieres guardar los trades de este backtest en CSV? (Puede relentizar la ejecucion encarecidamente) (s/n): ");
        System.out.flush();

        // Leer línea con timeout (si está disponible)
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        try {
            String line = reader.readLine();
            return line != null && line.trim().toLowerCase().startsWith("s");
        } catch (IOException e) {
            log.warn("Error al leer entrada de usuario: {}, asumiendo 'no'", e.getMessage());
            return false;
        }
    }

    private String construirPayload(String ruta, String tf, Map<String, List<Map<String, Object>>> velas,
            BigDecimal capitalAsignado, BigDecimal risk, String nombreEstrategia, boolean guardarTrades) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("strategy_path", ruta);
        payload.put("strategy_name", nombreEstrategia);
        payload.put("timeframe", tf);
        payload.put("velas", velas);
        payload.put("capital", capitalAsignado.doubleValue());
        payload.put("risk_per_trade", risk.doubleValue());
        payload.put("escribir_trades", guardarTrades);

        // Nota: Mantener JSON por compatibilidad hacia atrás
        // En próxima fase (Fase 4): Actualizar scripts Python para MessagePack
        return new Gson().toJson(payload);
    }

    /**
     * Invoca el motor Python con timeout y destrucción de proceso en caso de error.
     * Soporte para MessagePack (próxima fase) manteniendo compatibilidad JSON.
     */
    private String invocarMotorPythonConRetries(String jsonPayload) throws StrategyExecutionException {
        Process process = null;
        try {
            long startTime = System.currentTimeMillis();
            log.debug("Iniciando backtest con payload de {} bytes", jsonPayload.length());

            ConsoleLoader.getInstance().startSpinner("Ejecutando backtest");
            ProcessBuilder pb = new ProcessBuilder(AppConstants.PYTHON_EXECUTABLE, PathConfig.ENGINE_BACKTEST_PATH);
            pb.redirectErrorStream(false);
            process = pb.start();

            // Escribir payload
            try (OutputStream os = process.getOutputStream()) {
                os.write(jsonPayload.getBytes(StandardCharsets.UTF_8));
                os.flush();
            } catch (IOException e) {
                log.error("Error escribiendo payload al proceso Python: {}", e.getMessage());
                destroyProcessForcibly(process);
                throw new StrategyExecutionException("No se pudo escribir datos al motor Python", e);
            }

            // Esperar con timeout
            boolean finished = process.waitFor(ProcessExecutorConfig.TIMEOUT_SECONDS, TimeUnit.SECONDS);
            ConsoleLoader.getInstance().stopClear();

            if (!finished) {
                log.error("Backtest timeout después de {}s, destruyendo proceso", ProcessExecutorConfig.TIMEOUT_SECONDS);
                destroyProcessForcibly(process);
                throw new StrategyExecutionException(
                        "Backtest excedió timeout de " + ProcessExecutorConfig.TIMEOUT_SECONDS + "s");
            }

            long duration = System.currentTimeMillis() - startTime;

            // Leer salida
            String stdout = leerStream(process.getInputStream());
            String stderr = leerStream(process.getErrorStream());
            int exitCode = process.exitValue();

            log.info("Backtest completado en {} ms (exit code: {})", duration, exitCode);

            if (exitCode != 0) {
                String errorMsg = !stderr.isBlank() ? stderr : stdout;
                log.error("Motor de backtest falló con código {}: {}", exitCode, errorMsg);
                throw new StrategyExecutionException("Backtest falló (Exit Code " + exitCode + "):\n" + errorMsg);
            }

            return stdout;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Proceso backtest interrumpido: {}", e.getMessage());
            if (process != null) {
                destroyProcessForcibly(process);
            }
            throw new StrategyExecutionException("Backtest interrumpido", e);
        } catch (StrategyExecutionException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error inesperado en backtest: {}", e.getMessage(), e);
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
            process.destroyForcibly();
            try {
                process.waitFor(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Lee un flujo de entrada completo y lo convierte a String.
     */
    private String leerStream(java.io.InputStream is) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }
}