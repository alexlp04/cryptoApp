package com.bottrading.trading.infrastructure.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.bottrading.strategy.domain.InstanciaEstrategia;

class RealtimeProcessSupervisorTest {

    private final RealtimeProcessSupervisor supervisor =
            new RealtimeProcessSupervisor(mock(PythonBridgeFacade.class));

    private InstanciaEstrategia instancia(boolean real, BigDecimal risk) {
        return InstanciaEstrategia.inicializar(null, null, "1h", List.of("BTCUSDT"), real, 1L,
                risk, new BigDecimal("1000"));
    }

    @Test
    @DisplayName("El payload RT lleva is_real y risk_per_trade de la instancia (#85 #92)")
    void should_send_is_real_and_risk_per_trade() {
        Map<String, Object> payload =
                supervisor.construirPayload(instancia(true, new BigDecimal("0.05")), List.of("BTCUSDT"), java.util.Map.of());

        assertEquals(true, payload.get("is_real"));
        assertEquals(new BigDecimal("0.05"), payload.get("risk_per_trade"));
    }

    @Test
    @DisplayName("Sin riesgo definido se omite risk_per_trade y Python usa su valor por defecto")
    void should_omit_risk_when_null() {
        Map<String, Object> payload =
                supervisor.construirPayload(instancia(false, null), List.of("BTCUSDT"), java.util.Map.of());

        assertEquals(false, payload.get("is_real"));
        assertFalse(payload.containsKey("risk_per_trade"));
        assertTrue(payload.containsKey("capital"));
    }

    @Test
    @DisplayName("El payload RT lleva el modo de señal de la instancia (#85)")
    void should_send_signal_mode() {
        InstanciaEstrategia i = instancia(false, new BigDecimal("0.02"));
        i.setSignalMode("both");

        Map<String, Object> payload = supervisor.construirPayload(i, List.of("BTCUSDT"), java.util.Map.of());

        assertEquals("both", payload.get("signal_mode"));
        assertEquals("close", supervisor.construirPayload(instancia(false, null), List.of("BTCUSDT"), java.util.Map.of()).get("signal_mode"));
    }

    @Test
    @DisplayName("Las posiciones abiertas viajan al motor para que siga vigilando su stop y su objetivo")
    void open_positions_are_sent_to_the_engine() {
        java.util.Map<String, java.util.Map<String, Object>> abiertas =
                java.util.Map.of("BTCUSDT", java.util.Map.of("stop", 95.0, "take_profit", 110.0));

        Map<String, Object> payload = supervisor.construirPayload(
                instancia(false, new BigDecimal("0.05")), List.of("BTCUSDT"), abiertas);

        assertEquals(abiertas, payload.get("open_positions"));
    }
}
