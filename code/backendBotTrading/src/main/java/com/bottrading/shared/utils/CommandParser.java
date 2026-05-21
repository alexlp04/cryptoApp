package com.bottrading.shared.utils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Utilidad para parsear comandos de consola con banderas (flags).
 * Convierte un array de strings en un objeto fácilmente consultable.
 */
public class CommandParser {

    private boolean isReal = false;
    private boolean isVirtual = false;
    private String estrategia = null;
    private String modelo = null;
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
                    parseDays(requireValue(parts, index));
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
                    parseNTrials(requireValue(parts, index));
                    index += 2;
                }
                case "-cv-folds", "-cv" -> {
                    parseCvFolds(requireValue(parts, index));
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

    public boolean isReal() {
        return isReal;
    }

    public boolean isVirtual() {
        return isVirtual;
    }

    public String getEstrategia() {
        return estrategia;
    }

    public String getModelo() {
        return modelo;
    }

    public String getTimeframe() {
        return timeframe;
    }

    public void setTimeframe(String timeframe) {
        this.timeframe = timeframe;
    }

    public List<String> getCoins() {
        return coins;
    }

    public Integer getDays() {
        return days;
    }

    public boolean isDetectGaps() {
        return detectGaps;
    }

    public List<String> getPositionalArgs() {
        return positionalArgs;
    }

    public boolean hasErrorSintaxis() {
        return errorSintaxis;
    }

    public String getMensajeError() {
        return mensajeError;
    }

    public Map<String, Object> getHyperparams() { return hyperparams; }

    public Double getMinComposite() { return minComposite; }

    public Integer getNTrials() { return nTrials; }

    public Integer getCvFolds() { return cvFolds; }


    private int parseCoins(String[] parts, int startIndex) {
        int index = startIndex;
        while (index < parts.length && !parts[index].startsWith("-")) {
            coins.add(parts[index].toUpperCase());
            index++;
        }
        return index;
    }

    private void parseDays(String value) {
        try {
            days = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            this.errorSintaxis = true;
            this.mensajeError = "Error de sintaxis: El valor para -days debe ser un número entero.";
        }
    }

    private void parseMinComposite(String value) {
        try {
            double parsed = Double.parseDouble(value);
            if (parsed <= 0 || parsed > 100) {
                this.errorSintaxis = true;
                this.mensajeError = "Error de sintaxis: -min-composite debe estar entre 1 y 100.";
            } else {
                this.minComposite = parsed;
            }
        } catch (NumberFormatException e) {
            this.errorSintaxis = true;
            this.mensajeError = "Error de sintaxis: El valor para -min-composite debe ser un número (ej: 60)";
        }
    }

    private void parseNTrials(String value) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed <= 0) {
                this.errorSintaxis = true;
                this.mensajeError = "Error de sintaxis: -n-trials debe ser un entero positivo.";
            } else {
                this.nTrials = parsed;
            }
        } catch (NumberFormatException e) {
            this.errorSintaxis = true;
            this.mensajeError = "Error de sintaxis: El valor para -n-trials debe ser un número entero (ej: 50)";
        }
    }

    private void parseCvFolds(String value) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 2 || parsed > 20) {
                this.errorSintaxis = true;
                this.mensajeError = "Error de sintaxis: -cv-folds debe estar entre 2 y 20.";
            } else {
                this.cvFolds = parsed;
            }
        } catch (NumberFormatException e) {
            this.errorSintaxis = true;
            this.mensajeError = "Error de sintaxis: El valor para -cv-folds debe ser un número entero (ej: 5)";
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
            this.errorSintaxis = true;
            this.mensajeError = "Error de sintaxis: Te ha faltado indicar un valor después de una bandera.";
            return "";
        }
        return parts[valueIndex];
    }


}