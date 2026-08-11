package com.bottrading.shared.utils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

/**
 * Utilidad para parsear comandos de consola con banderas (flags).
 * Convierte un array de strings en un objeto fácilmente consultable.
 */
@Getter
public class CommandParser {

    private boolean isReal = false;
    private boolean isVirtual = false;
    private String estrategia = null;
    private String modelo = null;
    @Setter
    private String timeframe = null;
    private Integer days = null;
    private boolean detectGaps = false;
    private final List<String> coins = new ArrayList<>();
    private final List<String> positionalArgs = new ArrayList<>();
    private Map<String, Object> hyperparams = new HashMap<>();
    private Double minComposite = null;
    private Integer nTrials = null;
    private Integer cvFolds = null;

    // Variables de control de errores
    // El accesor se llama hasErrorSintaxis(), no isErrorSintaxis(): se mantiene a mano.
    @Getter(AccessLevel.NONE)
    private boolean errorSintaxis = false;
    private String mensajeError = null;

    public CommandParser(String[] parts) {
        parse(parts);
    }

    private void parse(String[] parts) {
        int index = 1; // parts[0] es el nombre del comando (ej: "trade")

        while (index < parts.length && !errorSintaxis) {
            String arg = parts[index].toLowerCase();

            switch (arg) {
                case "-r" -> {
                    isReal = true;
                    index++;
                }
                case "-v" -> {
                    isVirtual = true;
                    index++;
                }
                case "--detect-gaps" -> {
                    detectGaps = true;
                    index++;
                }
                case "-strategy", "-s" -> {
                    estrategia = requireValue(parts, index);
                    index += 2;
                }
                case "-model", "-m" -> {
                    modelo = requireValue(parts, index);
                    index += 2;
                }
                case "-tf", "-t" -> {
                    timeframe = requireValue(parts, index);
                    index += 2;
                }
                case "-days", "--days", "-d" -> {
                    days = parseEntero(requireValue(parts, index), "-days", ".",
                            Integer.MIN_VALUE, Integer.MAX_VALUE, null);
                    index += 2;
                }
                case "-coins", "-c" -> index = parseCoins(parts, index + 1);
                case "-params", "-p" -> {
                    parseParams(requireValue(parts, index));
                    index += 2;
                }
                case "-min-composite", "-mc" -> {
                    parseMinComposite(requireValue(parts, index));
                    index += 2;
                }
                case "-n-trials", "-nt" -> {
                    nTrials = parseEntero(requireValue(parts, index), "-n-trials", " (ej: 50)",
                            1, Integer.MAX_VALUE, "-n-trials debe ser un entero positivo.");
                    index += 2;
                }
                case "-cv-folds", "-cv" -> {
                    cvFolds = parseEntero(requireValue(parts, index), "-cv-folds", " (ej: 5)",
                            2, 20, "-cv-folds debe estar entre 2 y 20.");
                    index += 2;
                }
                default -> {
                    if (!arg.startsWith("-")) {
                        positionalArgs.add(parts[index]);
                    }
                    index++;
                }
            }
        }
    }

    public boolean hasErrorSintaxis() {
        return errorSintaxis;
    }

    private void fallo(String mensaje) {
        this.errorSintaxis = true;
        this.mensajeError = mensaje;
    }

    /**
     * Parsea un entero y valida que caiga en [min, max].
     * Devuelve null y marca error de sintaxis si no es un entero o se sale del rango.
     *
     * @param ejemplo  sufijo del mensaje de formato (ej: " (ej: 50)" o ".")
     * @param rangoMsg mensaje de rango, o null si la bandera no acota valores
     */
    private Integer parseEntero(String value, String flag, String ejemplo, int min, int max, String rangoMsg) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < min || parsed > max) {
                fallo("Error de sintaxis: " + rangoMsg);
                return null;
            }
            return parsed;
        } catch (NumberFormatException e) {
            fallo("Error de sintaxis: El valor para " + flag + " debe ser un número entero" + ejemplo);
            return null;
        }
    }

    private int parseCoins(String[] parts, int startIndex) {
        int index = startIndex;
        while (index < parts.length && !parts[index].startsWith("-")) {
            coins.add(parts[index].toUpperCase());
            index++;
        }
        return index;
    }

    private void parseMinComposite(String value) {
        try {
            double parsed = Double.parseDouble(value);
            if (parsed <= 0 || parsed > 100) {
                fallo("Error de sintaxis: -min-composite debe estar entre 1 y 100.");
            } else {
                this.minComposite = parsed;
            }
        } catch (NumberFormatException e) {
            fallo("Error de sintaxis: El valor para -min-composite debe ser un número (ej: 60)");
        }
    }

    private void parseParams(String rawParams) {
        for (String pair : rawParams.split(",")) {
            parseSingleParam(pair);
        }
    }

    private void parseSingleParam(String pair) {
        String[] kv = pair.split("=");
        if (kv.length != 2) {
            return;
        }

        String key = kv[0].trim();
        String value = kv[1].trim();
        hyperparams.put(key, parseTypedValue(value));
    }

    private Object parseTypedValue(String value) {
        try {
            if (value.contains(".")) {
                return Double.parseDouble(value);
            }
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return value;
        }
    }

    private String requireValue(String[] parts, int flagIndex) {
        int valueIndex = flagIndex + 1;
        if (valueIndex >= parts.length || parts[valueIndex].startsWith("-")) {
            fallo("Error de sintaxis: Te ha faltado indicar un valor después de una bandera.");
            return "";
        }
        return parts[valueIndex];
    }
}