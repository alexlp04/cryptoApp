package com.bottrading.shared.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SafeParserTest {

    @Test
    @DisplayName("toInt acepta números y textos numéricos y devuelve el valor por defecto con lo demás")
    void to_int() {
        assertEquals(7, SafeParser.toInt(7));
        assertEquals(42, SafeParser.toInt("42"));
        assertEquals(-1, SafeParser.toInt("4.2", -1));
        assertEquals(-1, SafeParser.toInt("abc", -1));
        assertEquals(0, SafeParser.toInt(null));
    }

    @Test
    @DisplayName("toBigDecimal conserva la precisión del texto y no lanza ante basura")
    void to_big_decimal() {
        assertEquals(new BigDecimal("0.10"), SafeParser.toBigDecimal("0.10"));
        assertEquals(new BigDecimal("1E+3"), SafeParser.toBigDecimal("1E3"));
        assertEquals(BigDecimal.TEN, SafeParser.toBigDecimal("no", BigDecimal.TEN));
        assertEquals(BigDecimal.ZERO, SafeParser.toBigDecimal(null));
    }

    @Test
    @DisplayName("toString usa el valor por defecto solo con null")
    void to_string() {
        assertEquals("x", SafeParser.toString(null, "x"));
        assertEquals("", SafeParser.toString(null));
        assertEquals("5", SafeParser.toString(5));
    }
}
