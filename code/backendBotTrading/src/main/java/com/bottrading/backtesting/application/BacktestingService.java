package com.bottrading.backtesting.application;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.bottrading.config.ProcessExecutorConfig;
import com.bottrading.market.domain.Vela;
import com.bottrading.shared.exceptions.StrategyExecutionException;
import com.bottrading.trading.infrastructure.bridge.PythonBridgeExecutionException;
import com.bottrading.trading.infrastructure.bridge.PythonBridgeFacade;
import com.bottrading.trading.infrastructure.bridge.PythonBridgeRequest;
import com.bottrading.trading.infrastructure.bridge.protocol.IpcMessagePackCodec;
import com.bottrading.trading.infrastructure.bridge.protocol.IpcMessageType;
import com.bottrading.shared.utils.ConsoleLoader;
import com.bottrading.shared.utils.PathConfig;
import com.google.gson.Gson;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class BacktestingService {

    private final Gson gson = new Gson();
    private final PythonBridgeFacade pythonBridgeFacade;

    public BacktestingService(PythonBridgeFacade pythonBridgeFacade) {
        this.pythonBridgeFacade = pythonBridgeFacade;
    }

    // ORQUESTACIÓN
    public String ejecutarBacktest(String rutaEstrategia, String nombreEstrategia, String timeframe,
            Map<String, List<Vela>> velasPorSimbolo, BigDecimal capitalAsignado, BigDecimal risk,
            boolean guardarTrades)
            throws StrategyExecutionException {
        ConsoleLoader.getInstance().startDots("Transformando datos");
        Map<String, List<Map<String, Object>>> velasMapeadas = transformarVelasParaPython(velasPorSimbolo);
        ConsoleLoader.getInstance().stopClear();

        String payload = construirPayload(rutaEstrategia, timeframe, velasMapeadas, capitalAsignado, risk,
                nombreEstrategia, guardarTrades);

        return invocarMotorPythonConRetries(payload);
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
        m.put("open", v.getOpen());
        m.put("high", v.getHigh());
        m.put("low", v.getLow());
        m.put("close", v.getClose());
        m.put("volume", v.getVolume());
        return m;
    }

    private String construirPayload(String ruta, String tf, Map<String, List<Map<String, Object>>> velas,
            BigDecimal capitalAsignado, BigDecimal risk, String nombreEstrategia, boolean guardarTrades) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("strategy_path", ruta);
        payload.put("strategy_name", nombreEstrategia);
        payload.put("timeframe", tf);
        payload.put("velas", velas);
        payload.put("capital", capitalAsignado);
        payload.put("risk_per_trade", risk);
        payload.put("escribir_trades", guardarTrades);
        return gson.toJson(payload);
    }

    /**
     * Invoca el motor Python con timeout y destrucción de proceso en caso de error.
     * Soporte para MessagePack (próxima fase) manteniendo compatibilidad JSON.
     */
    private String invocarMotorPythonConRetries(String jsonPayload) throws StrategyExecutionException {
        try {
            long startTime = System.currentTimeMillis();
            log.debug("Iniciando backtest con payload de {} bytes", jsonPayload.length());

            ConsoleLoader.getInstance().startSpinner("Ejecutando backtest");
            PythonBridgeRequest<String> request = PythonBridgeRequest.<String>builder(PathConfig.ENGINE_BACKTEST_PATH)
                    .operationName("backtest")
                    .noTimeout()
                    .maxRetries(ProcessExecutorConfig.MAX_RETRIES)
                    .retryDelayMs(ProcessExecutorConfig.RETRY_DELAY_MS)
                    .stdinWriter(os -> escribirEnvelopeBacktest(os, jsonPayload))
                    .stdoutReader(this::parseResponseWithFallback)
                    .onStderrLine(line -> log.info("PY [backtest]: {}", line))
                    .build();

            String stdout = pythonBridgeFacade.execute(request);

            ConsoleLoader.getInstance().stopClear();
            long duration = System.currentTimeMillis() - startTime;
            log.info("Backtest completado en {} ms", duration);
            return stdout;
        } catch (PythonBridgeExecutionException e) {
            ConsoleLoader.getInstance().stopClear();
            throw new StrategyExecutionException("Backtest falló: " + e.getMessage(), e);
        } catch (StrategyExecutionException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error inesperado en backtest: {}", e.getMessage(), e);
            ConsoleLoader.getInstance().stopClear();
            throw new StrategyExecutionException("Error inesperado: " + e.getMessage(), e);
        }
    }

    /**
     * Lee un flujo de entrada completo y lo convierte a String.
     */
    private void escribirEnvelopeBacktest(java.io.OutputStream outputStream, String jsonPayload) throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = gson.fromJson(jsonPayload, Map.class);
        IpcMessagePackCodec.writeEnvelope(outputStream, IpcMessageType.BACKTEST_REQUEST, payload);
    }

    private String leerEnvelopeBacktest(InputStream inputStream) throws IOException {
        Map<String, Object> envelope = IpcMessagePackCodec.readEnvelope(inputStream);
        Object payload = envelope.get("payload");
        return gson.toJson(payload == null ? Map.of() : payload);
    }

    private String parseResponseWithFallback(InputStream inputStream) throws IOException {
        return leerEnvelopeBacktest(inputStream);
    }
}