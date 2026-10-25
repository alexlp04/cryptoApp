package com.bottrading.trading.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RiskRulesTest {

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    @Test
    @DisplayName("El tamaño arriesga exactamente equity * riesgo hasta el stop")
    void risks_the_configured_fraction() {
        // 10 000 de equity al 1 % = 100 de riesgo; stop a 5 de distancia → 20 unidades (2 000 de nocional)
        RiskRules.Sizing t = RiskRules.dimensionar(d("10000"), d("0.01"), d("100"), d("95"), d("10000"));

        assertEquals(0, t.unidades().compareTo(d("20")));
        assertEquals(0, t.riesgo().compareTo(d("100")));
        assertEquals(0, t.nocional().compareTo(d("2000")));
    }

    @Test
    @DisplayName("Un stop estrecho lo limita el 25 % del equity, y el capital disponible si es menor")
    void caps_the_position() {
        RiskRules.Sizing porEquity = RiskRules.dimensionar(d("10000"), d("0.01"), d("100"), d("99.9"), d("10000"));
        RiskRules.Sizing porDisponible = RiskRules.dimensionar(d("10000"), d("0.01"), d("100"), d("99.9"), d("500"));

        assertEquals(0, porEquity.nocional().compareTo(d("2500")));
        assertEquals(0, porDisponible.nocional().compareTo(d("500")));
        assertTrue(porEquity.riesgo().compareTo(d("100")) < 0); // con el tope se arriesga menos
    }

    @Test
    @DisplayName("Sin stop válido, equity o disponible no se dimensiona")
    void rejects_unsizable_trades() {
        assertNull(RiskRules.dimensionar(d("10000"), d("0.01"), d("100"), null, d("10000")));
        assertNull(RiskRules.dimensionar(d("10000"), d("0.01"), d("100"), d("100"), d("10000")));
        assertNull(RiskRules.dimensionar(d("10000"), d("0.01"), d("100"), d("120"), d("10000")));
        assertNull(RiskRules.dimensionar(d("10000"), d("0.01"), d("100"), d("0"), d("10000")));
        assertNull(RiskRules.dimensionar(d("0"), d("0.01"), d("100"), d("95"), d("10000")));
        assertNull(RiskRules.dimensionar(d("10000"), d("0.01"), d("100"), d("95"), d("0")));
        assertFalse(RiskRules.stopValido(d("100"), null));
    }

    @Test
    @DisplayName("El riesgo abierto total no supera el 6 % del equity")
    void limits_total_open_risk() {
        assertFalse(RiskRules.superaRiesgoAbierto(d("10000"), d("500"), d("100"))); // 600 = límite exacto
        assertTrue(RiskRules.superaRiesgoAbierto(d("10000"), d("500"), d("100.01")));
    }

    @Test
    @DisplayName("Los costes coinciden con los del backtest: 0,05 % de slippage y 0,1 % por lado")
    void execution_costs_match_the_backtest() {
        assertEquals(0, ExecutionCosts.precioCompra(d("100")).compareTo(d("100.05")));
        assertEquals(0, ExecutionCosts.precioVenta(d("100"), false).compareTo(d("99.95")));
        assertEquals(0, ExecutionCosts.precioVenta(d("100"), true).compareTo(d("100")));
        // 2 unidades: 0,001 * 2 * (100,05 + 99,95) = 0,4
        assertEquals(0, ExecutionCosts.comision(d("2"), d("100.05"), d("99.95")).compareTo(d("0.4")));
    }

    @Test
    @DisplayName("Un corto lleva el stop por encima de la entrada y arriesga la misma fracción del equity")
    void sizes_a_short_with_the_stop_above_the_entry() {
        assertTrue(RiskRules.stopValido(d("100"), d("105"), Lado.SHORT));
        assertFalse(RiskRules.stopValido(d("100"), d("95"), Lado.SHORT));
        assertFalse(RiskRules.stopValido(d("100"), d("105"))); // el mismo stop no vale para un largo

        RiskRules.Sizing corto = RiskRules.dimensionar(d("10000"), d("0.01"), d("100"), d("105"), d("10000"),
                Lado.SHORT);

        assertEquals(0, corto.riesgo().compareTo(d("100"))); // 1 % de 10 000
        assertNull(RiskRules.dimensionar(d("10000"), d("0.01"), d("100"), d("95"), d("10000"), Lado.SHORT));
    }

    @Test
    @DisplayName("Un corto vende a mercado con slippage en contra y se cubre comprando; el objetivo es límite")
    void short_execution_costs_mirror_the_long_ones() {
        assertEquals(0, ExecutionCosts.precioVenta(d("100"), false).compareTo(d("99.95"))); // apertura
        assertEquals(0, ExecutionCosts.precioCompra(d("100"), false).compareTo(d("100.05"))); // stop o cover
        assertEquals(0, ExecutionCosts.precioCompra(d("100"), true).compareTo(d("100"))); // objetivo
    }
}
