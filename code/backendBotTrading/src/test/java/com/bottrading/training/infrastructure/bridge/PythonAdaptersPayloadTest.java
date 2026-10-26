package com.bottrading.training.infrastructure.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.bottrading.market.domain.Vela;
import com.bottrading.trading.infrastructure.bridge.PythonBridgeFacade;
import com.bottrading.trading.infrastructure.bridge.PythonBridgeRequest;
import com.bottrading.trading.infrastructure.bridge.protocol.IpcMessagePackCodec;
import com.bottrading.trading.infrastructure.bridge.protocol.IpcMessageType;
import com.bottrading.training.domain.OptimizationResult;
import com.bottrading.training.domain.TrainingResult;

/** Lo que Java manda a los motores de entrenamiento y optimización, y cómo lee lo que contestan. */
class PythonAdaptersPayloadTest {

    private static Vela vela(String symbol, long t) {
        return new Vela(symbol, "1h", t, new BigDecimal("1.5"), new BigDecimal("2"), new BigDecimal("1"),
                new BigDecimal("1.8"), new BigDecimal("10"), t + 3_599_999, BigDecimal.ONE, 1, BigDecimal.ONE,
                BigDecimal.ONE);
    }

    private static Map<String, List<Vela>> velas() {
        Map<String, List<Vela>> m = new LinkedHashMap<>();
        m.put("BTCUSDT", List.of(vela("BTCUSDT", 0), vela("BTCUSDT", 3_600_000)));
        m.put("ETHUSDT", List.of(vela("ETHUSDT", 0)));
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> payloadEnviado(PythonBridgeFacade bridge) throws Exception {
        ArgumentCaptor<PythonBridgeRequest<?>> captor = ArgumentCaptor.forClass(PythonBridgeRequest.class);
        verify(bridge).execute(captor.capture());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        captor.getValue().stdinWriter().accept(out);
        Map<String, Object> envelope = IpcMessagePackCodec.readEnvelope(new ByteArrayInputStream(out.toByteArray()));
        return (Map<String, Object>) envelope.get("payload");
    }

    @Test
    @DisplayName("Entrenamiento: warmup obligatorio, estrategia por nombre de módulo y velas de todos los símbolos")
    void training_payload() throws Exception {
        PythonBridgeFacade bridge = mock(PythonBridgeFacade.class);

        new PythonTrainingAdapter(bridge).ejecutarEntrenamiento("random_forest", Map.of("n_estimators", 10),
                "/x/strategies/StressTestStrategy.py", velas(), "1h", 42);

        Map<String, Object> p = payloadEnviado(bridge);
        assertEquals("BTCUSDT", p.get("symbol"));
        assertEquals(42, p.get("warmup_candles"));
        assertEquals("StressTestStrategy", p.get("strategy_name"));
        assertEquals(3, ((List<?>) p.get("dataset")).size());
        assertEquals(Map.of("n_estimators", 10), p.get("hyperparameters"));
    }

    @Test
    @DisplayName("Optimización: sin warmup no se manda la clave; con estrategia manda su nombre")
    void optimization_payload() throws Exception {
        PythonBridgeFacade bridge = mock(PythonBridgeFacade.class);

        new PythonOptimizationAdapter(bridge).ejecutarOptimizacion("svm", "/x/EmaCross.py", velas(), "4h", 0.5,
                null, 20, 3);

        Map<String, Object> p = payloadEnviado(bridge);
        assertEquals("EmaCross", p.get("strategy_name"));
        assertFalse(p.containsKey("warmup_candles"));
        assertEquals(20, p.get("n_trials"));
        assertEquals(3, p.get("cv_folds"));
    }

    private static ByteArrayInputStream respuesta(IpcMessageType tipo, Map<String, Object> payload) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        IpcMessagePackCodec.writeEnvelope(out, tipo, payload);
        return new ByteArrayInputStream(out.toByteArray());
    }

    @Test
    @DisplayName("Una respuesta de entrenamiento correcta trae la ruta del modelo, métricas y simulación")
    void training_success_is_parsed() throws Exception {
        TrainingResult r = new PythonTrainingAdapter(mock(PythonBridgeFacade.class)).leerTrainingResult(respuesta(
                IpcMessageType.TRAIN_RESPONSE, Map.of("status", "success", "model_saved_at", "rf_1h_BTCUSDT_S.pkl",
                        "metrics", Map.of("F1-Score", "55%"), "trading_simulation_test", Map.of("op_totales", 4))));

        assertTrue(r.success());
        assertTrue(r.modelPath().endsWith("rf_1h_BTCUSDT_S.pkl"));
        assertEquals(Map.of("F1-Score", "55%"), r.metrics());
        assertEquals(Map.of("op_totales", 4), r.tradingSimulation());
        assertNull(r.errorMessage());
    }

    @Test
    @DisplayName("«warning» de optimización (no alcanzó el mínimo) cuenta como resultado utilizable")
    void optimization_warning_is_success() throws Exception {
        OptimizationResult r = new PythonOptimizationAdapter(mock(PythonBridgeFacade.class)).leerOptimizationResult(
                respuesta(IpcMessageType.OPTIMIZE_RESPONSE, Map.of("status", "warning")));

        assertTrue(r.success());
    }

    @Test
    @DisplayName("Una respuesta sin status se trata como error, y si falla el puente el resultado no lanza")
    void missing_status_and_bridge_failure() throws Exception {
        TrainingResult r = new PythonTrainingAdapter(mock(PythonBridgeFacade.class))
                .leerTrainingResult(respuesta(IpcMessageType.TRAIN_RESPONSE, Map.of()));
        assertFalse(r.success());

        PythonBridgeFacade roto = mock(PythonBridgeFacade.class);
        org.mockito.Mockito.doThrow(new RuntimeException("boom")).when(roto).execute(any());
        TrainingResult fallo = new PythonTrainingAdapter(roto)
                .ejecutarEntrenamiento("rf", Map.of(), null, velas(), "1h", 1);
        assertFalse(fallo.success());
        assertEquals("boom", fallo.errorMessage());
    }
}
