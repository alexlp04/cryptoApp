package com.bottrading.training.infrastructure.bridge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
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
            double minComposite,
            Integer warmupCandles,
            int nTrials,
            int cvFolds) {
        try {
            Map<String, Object> payload = construirPayload(
                    modelType, strategyPath, symbolCandles, timeframe, minComposite, warmupCandles,
                    nTrials, cvFolds);

            PythonBridgeRequest<OptimizationResult> request = PythonBridgeRequest
                    .<OptimizationResult>builder(PathConfig.ENGINE_OPTIMIZE_PATH)
                    .operationName("optimize-hyperparams")
                    .noTimeout()
                    .inactivityTimeout(ProcessExecutorConfig.OPTIMIZE_INACTIVITY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    // No idempotente ni barato: un fallo del script debe llegar al usuario, no relanzar horas de trabajo.
                    .maxRetries(1)
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
            double minComposite,
            Integer warmupCandles,
            int nTrials,
            int cvFolds) {
        Map<String, Object> payload = new LinkedHashMap<>();
        String primarySymbol = EnginePayloads.simboloPrincipal(symbolCandles);

        payload.put("model_type", modelType);
        payload.put("symbol", primarySymbol);
        payload.put("timeframe", timeframe);
        payload.put("dataset", EnginePayloads.serializarVelas(symbolCandles));
        payload.put("min_composite", minComposite);
        payload.put("n_trials", nTrials);
        payload.put("cv_folds", cvFolds);

        if (strategyPath != null && !strategyPath.isBlank()) {
            payload.put("strategy_path", strategyPath);
            payload.put("strategy_name", EnginePayloads.nombreEstrategia(strategyPath));
            if (warmupCandles != null) {
                payload.put("warmup_candles", warmupCandles);
            }
        }

        return payload;
    }

    OptimizationResult leerOptimizationResult(InputStream inputStream) throws IOException {
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
                rawPayload.get("message") != null ? rawPayload.get("message")
                        : (rawPayload.get("error") != null ? rawPayload.get("error") : "Optimización fallida"));
        return new OptimizationResult(resultData, success, errorMessage);
    }
}
