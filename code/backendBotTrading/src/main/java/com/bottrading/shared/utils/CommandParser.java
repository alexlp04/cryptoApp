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
    private String signalMode = null;

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
                            1, Integer.MAX_VALUE, "-days debe ser un entero positivo.");
                    index += 2;
                }
                case "-signal", "-sg" -> {
                    String mode = requireValue(parts, index).toLowerCase();
                    if (!errorSintaxis && !java.util.Set.of("close", "open", "both").contains(mode)) {
                        fallo("Error de sintaxis: -signal debe ser close, open o both.");
                    }
                    signalMode = mode;
                    index += 2;
                }
                case "-coins", "-c" -> {
                    index = parseCoins(parts, index + 1);
                    if (coins.isEmpty() && !errorSintaxis) {
                        fallo("Error de sintaxis: -coins necesita al menos una moneda (ej: -coins BTCUSDT).");
                    }
                }
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
                    } else if (!esNumero(arg)) {
                        // Antes se ignoraba en silencio ("-strategie X") y el comando solo respondía "Uso: ...".
                        fallo("Error de sintaxis: bandera desconocida '" + parts[index] + "'.");
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
                fallo("Error de sintaxis: -min-composite debe ser mayor que 0 y como máximo 100.");
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
        // limit 2: "a=b=c" conserva "b=c" como valor en vez de descartarse
        String[] kv = pair.split("=", 2);
        String key = kv[0].trim();
        String value = kv.length == 2 ? kv[1].trim() : "";
        if (key.isEmpty() || value.isEmpty()) {
            fallo("Error de sintaxis: par de -params inválido '" + pair + "' (usa clave=valor, ej: max_depth=6).");
            return;
        }
        hyperparams.put(key, parseTypedValue(value));
    }

    /** Boolean, entero, long o decimal (incluida la notación científica 1e-3); si no, texto. */
    private Object parseTypedValue(String value) {
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
            return Boolean.parseBoolean(value);
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignoredNotInt) {
            // no cabe en int: se prueba long y luego decimal
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignoredNotLong) {
            // no es entero: se prueba decimal
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return value;
        }
    }

    private static boolean esNumero(String value) {
        try {
            Double.parseDouble(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private String requireValue(String[] parts, int flagIndex) {
        int valueIndex = flagIndex + 1;
        // Un valor puede ser un número negativo (-d -5 debe dar "debe ser positivo", no "falta valor").
        if (valueIndex >= parts.length
                || (parts[valueIndex].startsWith("-") && !esNumero(parts[valueIndex]))) {
            fallo("Error de sintaxis: Te ha faltado indicar un valor después de " + parts[flagIndex] + ".");
            return "";
        }
        return parts[valueIndex];
    }
}