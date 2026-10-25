package com.bottrading.interfaces.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CliInputValidatorTest {

    @Test
    @DisplayName("El riesgo por trade solo es válido en (0, 1] (#65)")
    void risk_must_be_in_zero_exclusive_one_inclusive() {
        assertTrue(CliInputValidator.isValidRisk(new BigDecimal("0.01")));
        assertTrue(CliInputValidator.isValidRisk(BigDecimal.ONE));
        assertFalse(CliInputValidator.isValidRisk(BigDecimal.ZERO));
        assertFalse(CliInputValidator.isValidRisk(new BigDecimal("-0.5")));
        assertFalse(CliInputValidator.isValidRisk(new BigDecimal("1.5")));
        assertFalse(CliInputValidator.isValidRisk(null));
    }
}
