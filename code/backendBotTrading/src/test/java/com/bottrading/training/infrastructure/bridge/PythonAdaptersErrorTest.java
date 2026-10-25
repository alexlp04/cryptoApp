package com.bottrading.training.infrastructure.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.bottrading.trading.infrastructure.bridge.PythonBridgeFacade;
import com.bottrading.trading.infrastructure.bridge.protocol.IpcMessagePackCodec;
import com.bottrading.trading.infrastructure.bridge.protocol.IpcMessageType;
import com.bottrading.training.domain.OptimizationResult;
import com.bottrading.training.domain.TrainingResult;

class PythonAdaptersErrorTest {

    private static ByteArrayInputStream respuesta(IpcMessageType type, Map<String, Object> payload) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        IpcMessagePackCodec.writeEnvelope(out, type, payload);
        return new ByteArrayInputStream(out.toByteArray());
    }

    @Test
    @DisplayName("El mensaje de error de Python (clave 'message') llega al resultado de entrenamiento (#66)")
    void training_error_message_reaches_the_result() throws IOException {
        PythonTrainingAdapter adapter = new PythonTrainingAdapter(mock(PythonBridgeFacade.class));

        TrainingResult result = adapter.leerTrainingResult(
                respuesta(IpcMessageType.TRAIN_RESPONSE, Map.of("status", "error", "message", "dataset vacío")));

        assertFalse(result.success());
        assertEquals("dataset vacío", result.errorMessage());
    }

    @Test
    @DisplayName("El mensaje de error de Python llega al resultado de optimización (#66)")
    void optimization_error_message_reaches_the_result() throws IOException {
        PythonOptimizationAdapter adapter = new PythonOptimizationAdapter(mock(PythonBridgeFacade.class));

        OptimizationResult result = adapter.leerOptimizationResult(
                respuesta(IpcMessageType.OPTIMIZE_RESPONSE, Map.of("status", "error", "message", "ningún trial")));

        assertFalse(result.success());
        assertEquals("ningún trial", result.errorMessage());
    }
}
