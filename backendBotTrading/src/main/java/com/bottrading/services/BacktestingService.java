package com.bottrading.services;

import java.io.BufferedReader;
import java.io.Console;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.stream.Collectors;

import com.bottrading.beans.Vela;
import com.bottrading.exceptions.StrategyExecutionException;
import com.bottrading.utils.AppConstants;
import com.bottrading.utils.ConsoleLoader;
import com.bottrading.utils.PathConfig;
import com.google.gson.Gson;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Service orchestrating backtesting simulations.
 * Acts as a bridge between historical market data in Java and the calculation
 * engine in Python.
 */
@Slf4j
@Service
public class BacktestingService {

    private final Gson gson = new Gson();

    /**
     * Método principal: Orquestador.
     * Su complejidad cognitiva ahora es mínima porque solo llama a otros métodos.
     */
    public String ejecutarBacktest(String rutaEstrategia, String nombreEstrategia, String timeframe,
            Map<String, List<Vela>> velasPorSimbolo, BigDecimal capitalAsignado, BigDecimal risk)
            throws StrategyExecutionException {
        // 1. Preparar datos
        ConsoleLoader.getInstance().startDots("Transformando datos");
        Map<String, List<Map<String, Object>>> velasMapeadas = transformarVelasParaPython(velasPorSimbolo);
        ConsoleLoader.getInstance().stopClear();
        // 2. Construir Payload
        String jsonPayload = construirPayload(rutaEstrategia, timeframe, velasMapeadas, capitalAsignado, risk,
                nombreEstrategia);

        // 3. Ejecutar proceso externo
        return invocarMotorPython(jsonPayload);
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

    private String construirPayload(String ruta, String tf, Map<String, List<Map<String, Object>>> velas,
            BigDecimal capitalAsignado, BigDecimal risk, String nombreEstrategia) {
        System.out.print(
                "Quieres guardar los trades de este backtest en CSV? (Puede relentizar la ejecucion encarecidamente) (s/n): ");
        boolean escribir_trades = false;
        Scanner sc = new Scanner(System.in);
        if (sc.hasNextLine()) {
            String respuesta = sc.nextLine().trim().toLowerCase();
            if ("s".equals(respuesta)) {
                escribir_trades = true;
            } 
        }

        Map<String, Object> payload = Map.of(
                "strategy_path", ruta,
                "strategy_name", nombreEstrategia,
                "timeframe", tf,
                "velas", velas,
                "capital", capitalAsignado.doubleValue(),
                "risk_per_trade", risk.doubleValue(),
                "escribir_trades", escribir_trades);
        return gson.toJson(payload);
    }

    private String invocarMotorPython(String jsonPayload) throws StrategyExecutionException {
        try {
            long inicio = System.currentTimeMillis();
            log.debug("Initiating backtest with payload size: {} bytes", jsonPayload.length());
            ConsoleLoader.getInstance().startSpinner("Ejecutando backtest");
            ProcessBuilder pb = new ProcessBuilder(AppConstants.PYTHON_EXECUTABLE, PathConfig.ENGINE_BACKTEST_PATH);
            pb.redirectErrorStream(false);
            Process process = pb.start();

            // Write payload (input)
            try (OutputStream os = process.getOutputStream()) {
                os.write(jsonPayload.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
            ConsoleLoader.getInstance().stop("Backtest finalizado, procesando resultados");
            ConsoleLoader.getInstance().startSpinner("");
            // Read output
            String stdout = leerStream(process.getInputStream());
            String stderr = leerStream(process.getErrorStream());

            int exitCode = process.waitFor();

            long duracion = System.currentTimeMillis() - inicio;

            log.info("Backtesting completed in {} ms", duracion);

            if (exitCode != 0) {
                String errorMsg = stderr.isBlank() ? stdout : stderr;
                log.error("Backtesting engine failed with exit code {}: {}", exitCode, errorMsg);
                throw new StrategyExecutionException("Backtesting failed (Exit Code " + exitCode + "):\n" + errorMsg);
            }

            return stdout;
        } catch (IOException e) {
            log.error("I/O error during backtesting: {}", e.getMessage(), e);
            throw new StrategyExecutionException("I/O error in BacktestingService: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Backtesting process interrupted: {}", e.getMessage());
            throw new StrategyExecutionException("Backtesting process interrupted", e);
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