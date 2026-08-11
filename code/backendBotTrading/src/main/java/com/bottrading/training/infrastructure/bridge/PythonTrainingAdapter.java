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
import com.bottrading.training.application.port.out.TrainingEnginePort;
import com.bottrading.training.domain.TrainingResult;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class PythonTrainingAdapter implements TrainingEnginePort {

    private static final String STATUS_ERROR = "error";
    private static final String KEY_ERROR = "error";

    private final PythonBridgeFacade pythonBridgeFacade;

    @Override
    public TrainingResult ejecutarEntrenamiento(String modelType,
            Map<String, Object> hyperparameters,
            String strategyPath,
            Map<String, List<Vela>> symbolCandles,
            String timeframe,
            int warmupCandles) {
        try {
            Map<String, Object> payload = construirPayload(modelType, hyperparameters, strategyPath, symbolCandles,
                    timeframe, warmupCandles);

            PythonBridgeRequest<TrainingResult> request = PythonBridgeRequest
                    .<TrainingResult>builder(PathConfig.ENGINE_TRAIN_PATH)
                    .operationName("train-model")
                    .inactivityTimeout(ProcessExecutorConfig.TRAIN_INACTIVITY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .maxRetries(ProcessExecutorConfig.MAX_RETRIES)
                    .retryDelayMs(ProcessExecutorConfig.RETRY_DELAY_MS)
                    .stdinWriter(os -> IpcMessagePackCodec.writeEnvelope(os, IpcMessageType.TRAIN_REQUEST, payload))
                    .stdoutReader(this::leerTrainingResult)
                    .onStderrLine(line -> log.info("PY [train]: {}", line))
                    .build();

            return pythonBridgeFacade.execute(request);
        } catch (PythonBridgeExecutionException e) {
            log.error("Falló el motor de entrenamiento Python: {}", e.getMessage(), e);
            return new TrainingResult(null, Map.of(), Map.of(), false, e.getMessage());
        } catch (Exception e) {
            log.error("Error inesperado en el adapter de entrenamiento: {}", e.getMessage(), e);
            return new TrainingResult(null, Map.of(), Map.of(), false, e.getMessage());
        }
    }

    private Map<String, Object> construirPayload(String modelType,
            Map<String, Object> hyperparameters,
            String strategyPath,
            Map<String, List<Vela>> symbolCandles,
            String timeframe,
            int warmupCandles) {
        Map<String, Object> payload = new LinkedHashMap<>();
        String primarySymbol = symbolCandles.keySet().stream().findFirst().orElse("UNKNOWN");

        payload.put("model_type", modelType);
        payload.put("symbol", primarySymbol);
        payload.put("timeframe", timeframe);
        payload.put("dataset", serializarVelas(symbolCandles));
        payload.put("hyperparameters", hyperparameters == null ? Map.of() : hyperparameters);
        // Obligatorio: sin el, engine_train entrena sobre las velas de calentamiento,
        // cuyos indicadores aun no estan formados (engine_optimize si lo enviaba).
        payload.put("warmup_candles", warmupCandles);

        if (strategyPath != null && !strategyPath.isBlank()) {
            payload.put("strategy_path", strategyPath);
            payload.put("strategy_name", extraerNombreEstrategia(strategyPath));
        }

        return payload;
    }

    private List<Map<String, Object>> serializarVelas(Map<String, List<Vela>> symbolCandles) {
        List<Map<String, Object>> dataset = new ArrayList<>();

        for (Map.Entry<String, List<Vela>> entry : symbolCandles.entrySet()) {
            for (Vela vela : entry.getValue()) {
                Map<String, Object> row = new HashMap<>();
                row.put("symbol", entry.getKey());
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

    private TrainingResult leerTrainingResult(InputStream inputStream) throws IOException {
        Map<String, Object> envelope = IpcMessagePackCodec.readEnvelope(inputStream);
        Object payload = envelope.get("payload");
        if (!(payload instanceof Map<?, ?> rawPayload)) {
            return new TrainingResult(null, Map.of(), Map.of(), false,
                    "Respuesta de entrenamiento sin payload válido");
        }

        Object statusValue = rawPayload.containsKey("status") ? rawPayload.get("status") : STATUS_ERROR;
        String status = String.valueOf(statusValue);
        boolean success = "success".equalsIgnoreCase(status);
        String modelFileName = rawPayload.get("model_saved_at") == null
                ? null
                : String.valueOf(rawPayload.get("model_saved_at"));
        String modelPath = modelFileName == null ? null : Paths.get(PathConfig.MODELS_DIR, modelFileName).toString();

        @SuppressWarnings("unchecked")
        Map<String, Object> metrics = rawPayload.get("metrics") instanceof Map<?, ?> rawMetrics
                ? (Map<String, Object>) rawMetrics
                : Map.of();

        @SuppressWarnings("unchecked")
        Map<String, Object> tradingSimulation = rawPayload.get("trading_simulation_test") instanceof Map<?, ?> rawSim
            ? (Map<String, Object>) rawSim
            : Map.of();

        Object errorValue = rawPayload.containsKey(KEY_ERROR) ? rawPayload.get(KEY_ERROR) : "Entrenamiento fallido";
        String errorMessage = success ? null : String.valueOf(errorValue);

        return new TrainingResult(modelPath, metrics, tradingSimulation, success, errorMessage);
    }
}