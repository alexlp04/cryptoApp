package com.bottrading.utils;

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
    private final List<String> coins = new ArrayList<>();
    private Map<String, Object> hyperparams = new HashMap<>();

    // Variables de control de errores
    private boolean errorSintaxis = false;
    private String mensajeError = null;

    public CommandParser(String[] parts) {
        parse(parts);
    }

    private void parse(String[] parts) {
        try {
            // Empezamos en i = 1 porque parts[0] es el nombre del comando (ej: "trade")
            for (int i = 1; i < parts.length; i++) {
                String arg = parts[i].toLowerCase();
                switch (arg) {
                    case "-r" -> isReal = true;
                    case "-v" -> isVirtual = true;
                    case "-strategy", "-s" -> estrategia = parts[++i];
                    case "-model", "-m" -> modelo = parts[++i];
                    case "-tf", "-t" -> timeframe = parts[++i];
                    case "-days", "-d" -> i = getDaysFromArgs(parts, i);
                    case "-coins", "-c" -> i = getCoinsFromArgs(parts, i);
                    case "-params", "-p" -> i = getParamsFromArgs(parts, i);
                }
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            this.errorSintaxis = true;
            this.mensajeError = "Error de sintaxis: Te ha faltado indicar un valor después de una bandera.";
        }
    }

    // --- Getters ---
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

    public List<String> getCoins() {
        return coins;
    }

    public Integer getDays() {
        return days;
    }

    public boolean hasErrorSintaxis() {
        return errorSintaxis;
    }

    public String getMensajeError() {
        return mensajeError;
    }

    public Map<String, Object> getHyperparams() { return hyperparams; }


    private int getCoinsFromArgs(String[] parts, int i) {
        // Recogemos todas las monedas hasta encontrar el final u otra bandera
        while (i + 1 < parts.length && !parts[i + 1].startsWith("-")) {
            coins.add(parts[++i].toUpperCase());
        }
        return i;
    }

    private int getDaysFromArgs(String[] parts, int i) {
        try {
            days = Integer.parseInt(parts[++i]);
        } catch (NumberFormatException e) {
            this.errorSintaxis = true;
            this.mensajeError = "Error de sintaxis: El valor para -days debe ser un número entero.";
        }
        return i;
    }

    private int getParamsFromArgs(String[] parts, int i) {
        if (i + 1 < parts.length && !parts[i + 1].startsWith("-")) {
            String rawParams = parts[++i];
            String[] pairs = rawParams.split(","); // Separamos por comas
            
            for (String pair : pairs) {
                String[] kv = pair.split("="); // Separamos clave=valor
                if (kv.length == 2) {
                    String key = kv[0].trim();
                    String value = kv[1].trim();
                    
                    // Truco vital: Intentar convertir a número para que Python no falle
                    try {
                        if (value.contains(".")) {
                            hyperparams.put(key, Double.parseDouble(value)); // Decimales
                        } else {
                            hyperparams.put(key, Integer.parseInt(value)); // Enteros
                        }
                    } catch (NumberFormatException e) {
                        hyperparams.put(key, value); // Si no es número, se guarda como texto (ej: rbf)
                    }
                }
            }
        } else {
            this.errorSintaxis = true;
            this.mensajeError = "Error: Faltan valores para -params (ej: -params n_estimators=300,max_depth=5)";
        }
        return i;
    }


}