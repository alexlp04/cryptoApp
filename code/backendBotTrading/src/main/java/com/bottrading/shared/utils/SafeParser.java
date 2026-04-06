package com.bottrading.shared.utils;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Utilidad para parsing seguro de objetos con valores por defecto.
 * Centraliza la lógica de conversión tolerante a fallos.
 */
public class SafeParser {
    private SafeParser() {
    }

    /**
     * Convierte un objeto a Integer de forma segura.
     * @param value Objeto a convertir (puede ser null)
     * @param defaultValue Valor devuelto si falla la conversión
     * @return Integer convertido o defaultValue
     */
    public static int toInt(Object value, int defaultValue) {
        return Optional.ofNullable(value)
                .map(String::valueOf)
                .flatMap(s -> {
                    try {
                        return Optional.of(Integer.parseInt(s));
                    } catch (NumberFormatException e) {
                        return Optional.empty();
                    }
                })
                .orElse(defaultValue);
    }

    /**
     * Convierte un objeto a Integer, con valor default = 0.
     */
    public static int toInt(Object value) {
        return toInt(value, 0);
    }

    /**
     * Convierte un objeto a BigDecimal de forma segura.
     * @param value Objeto a convertir (puede ser null)
     * @param defaultValue Valor devuelto si falla la conversión
     * @return BigDecimal convertido o defaultValue
     */
    public static BigDecimal toBigDecimal(Object value, BigDecimal defaultValue) {
        return Optional.ofNullable(value)
                .map(String::valueOf)
                .flatMap(s -> {
                    try {
                        return Optional.of(new BigDecimal(s));
                    } catch (Exception e) {
                        return Optional.empty();
                    }
                })
                .orElse(defaultValue);
    }

    /**
     * Convierte un objeto a BigDecimal, con valor default = ZERO.
     */
    public static BigDecimal toBigDecimal(Object value) {
        return toBigDecimal(value, BigDecimal.ZERO);
    }

    /**
     * Convierte un objeto a Double de forma segura.
     */
    public static double toDouble(Object value, double defaultValue) {
        return Optional.ofNullable(value)
                .map(String::valueOf)
                .flatMap(s -> {
                    try {
                        return Optional.of(Double.parseDouble(s));
                    } catch (NumberFormatException e) {
                        return Optional.empty();
                    }
                })
                .orElse(defaultValue);
    }

    /**
     * Convierte un objeto a Double, con valor default = 0.0.
     */
    public static double toDouble(Object value) {
        return toDouble(value, 0.0);
    }

    /**
     * Obtiene el String de un objeto de forma segura.
     */
    public static String toString(Object value, String defaultValue) {
        return Optional.ofNullable(value)
                .map(String::valueOf)
                .orElse(defaultValue);
    }

    /**
     * Obtiene el String de un objeto, con valor default = "".
     */
    public static String toString(Object value) {
        return toString(value, "");
    }

    public static boolean toBoolean(Object value, boolean defaultValue) {
        return Optional.ofNullable(value)
                .map(String::valueOf)
                .map(String::toLowerCase)
                .map(s -> s.equals("true") || s.equals("1") || s.equals("yes"))
                .orElse(defaultValue);
    }

    public static boolean toBoolean(Object value) {
        return toBoolean(value, false);
    }

    public static long toLong(Object value, long defaultValue) {
        return Optional.ofNullable(value)
                .map(String::valueOf)
                .flatMap(s -> {
                    try {
                        return Optional.of(Long.parseLong(s));
                    } catch (NumberFormatException e) {
                        return Optional.empty();
                    }
                })
                .orElse(defaultValue);
    }

    public static long toLong(Object value) {
        return toLong(value, 0L);
    }
}
