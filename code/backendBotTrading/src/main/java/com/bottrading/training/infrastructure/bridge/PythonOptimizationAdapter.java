package com.bottrading.training.infrastructure.bridge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import com.bottrading.config.ProcessExecutorConfig;
import com.bottrading.market.domain.Vela;
import com.bottrading.shared.utils.PathConfig;
import com.bottrading.trading.infrastructure.bridge.PythonBridgeExecutionException;
import com.bottrading.trading.infrastructure.bridge.PythonBridgeFacade;
import com.bottrading.trading.infrastructure.bridge.PythonBridgeRequest;
import com.bottrading.trading.infrastructure.bridge.protocol.IpcMessagePackCodec;
import com.bottrading.trading.infrastructure.bridge.protocol.IpcMessageType;
import com.bottrading.training.application.port.out.OptimizationEnginePort;
import com.bottrading.training.domain.OptimizationResult;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class PythonOptimizationAdapter implements OptimizationEnginePort {

    private static final int DEFAULT_N_TRIALS = 100;
    private static final int DEFAULT_CV_FOLDS = 5;

    private final PythonBridgeFacade pythonBridgeFacade;

    @Override
    public OptimizationResult ejecutarOptimizacion(String modelType,
            String strategyPath,
            Map<String, List<Vela>> symbolCandles,
            String timeframe,
            double minAccuracy,
            Integer warmupCandles) {
        try {
            Map<String, Object> payload = construirPayload(
                    modelType, strategyPath, symbolCandles, timeframe, minAccuracy, warmupCandles);

            PythonBridgeRequest<OptimizationResult> request = PythonBridgeRequest
                    .<OptimizationResult>builder(PathConfig.ENGINE_OPTIMIZE_PATH)
                    .operationName("optimize-hyperparams")
                    .noTimeout()
                    .inactivityTimeout(ProcessExecutorConfig.OPTIMIZE_INACTIVITY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .maxRetries(ProcessExecutorConfig.MAX_RETRIES)
                    .retryDelayMs(ProcessExecutorConfig.RETRY_DELAY_MS)
                    .stdinWriter(os -> IpcMessagePackCodec.writeEnvelope(os, IpcMessageType.OPTIMIZE_REQUEST, payload))
                    .stdoutReader(this::leerOptimizationResult)
                    .onStderrLine(line -> log.info("PY [optimize]: {}", line))
                    .build();

            return pythonBridgeFacade.execute(request);
        } catch (PythonBridgeExecutionException e) {
            log.error("Falló el motor de optimización Python: {}", e.getMessage(), e);
            return new OptimizationResult(Map.of(), false, e.getMessage());
        } catch (Exception e) {
            log.error("Error inesperado en el adapter de optimización: {}", e.getMessage(), e);
            return new OptimizationResult(Map.of(), false, e.getMessage());
        }
    }

    private Map<String, Object> construirPayload(String modelType,
            String strategyPath,
            Map<String, List<Vela>> symbolCandles,
            String timeframe,
            double minAccuracy,
            Integer warmupCandles) {
        Map<String, Object> payload = new LinkedHashMap<>();
        String primarySymbol = symbolCandles.keySet().stream().findFirst().orElse("UNKNOWN");

        payload.put("model_type", modelType);
        payload.put("symbol", primarySymbol);
        payload.put("timeframe", timeframe);
        payload.put("dataset", serializarVelas(symbolCandles));
        payload.put("min_accuracy", minAccuracy);
        payload.put("n_trials", DEFAULT_N_TRIALS);
        payload.put("cv_folds", DEFAULT_CV_FOLDS);

        if (strategyPath != null && !strategyPath.isBlank()) {
            payload.put("strategy_path", strategyPath);
            payload.put("strategy_name", extraerNombreEstrategia(strategyPath));
            if (warmupCandles != null) {
                payload.put("warmup_candles", warmupCandles);
            }
        }

        return payload;
    }

    private List<Map<String, Object>> serializarVelas(Map<String, List<Vela>> symbolCandles) {
        List<Map<String, Object>> dataset = new ArrayList<>();
        for (Map.Entry<String, List<Vela>> entry : symbolCandles.entrySet()) {
            for (Vela vela : entry.getValue()) {
                Map<String, Object> row = new HashMap<>();
                row.put("timestamp", vela.getOpenTime());
                row.put("open", vela.getOpen());
                row.put("high", vela.getHigh());
                row.put("low", vela.getLow());
                row.put("close", vela.getClose());
                row.put("volume", vela.getVolume());
                dataset.add(row);
            }
        }
        return dataset;
    }

    private String extraerNombreEstrategia(String strategyPath) {
        Path path = Paths.get(strategyPath).getFileName();
        String fileName = path == null ? strategyPath : path.toString();
        return fileName.endsWith(".py") ? fileName.substring(0, fileName.length() - 3) : fileName;
    }

    private OptimizationResult leerOptimizationResult(InputStream inputStream) throws IOException {
        Map<String, Object> envelope = IpcMessagePackCodec.readEnvelope(inputStream);
        Object payload = envelope.get("payload");
        if (!(payload instanceof Map<?, ?> rawPayload)) {
            return new OptimizationResult(Map.of(), false, "Respuesta de optimización sin payload válido");
        }

        Object statusValue = rawPayload.containsKey("status") ? rawPayload.get("status") : "error";
        boolean success = "success".equalsIgnoreCase(String.valueOf(statusValue))
                || "warning".equalsIgnoreCase(String.valueOf(statusValue));

        @SuppressWarnings("unchecked")
        Map<String, Object> resultData = (Map<String, Object>) rawPayload;

        String errorMessage = success ? null : String.valueOf(
                rawPayload.containsKey("error") ? rawPayload.get("error") : "Optimización fallida");
        return new OptimizationResult(resultData, success, errorMessage);
    }
}
