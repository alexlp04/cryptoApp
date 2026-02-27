package com.bottrading.utils;

import java.util.ArrayList;
import java.util.List;

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
    private final List<String> coins = new ArrayList<>();

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
                    case "-coins", "-c" -> {
                        // Recogemos todas las monedas hasta encontrar el final u otra bandera
                        while (i + 1 < parts.length && !parts[i + 1].startsWith("-")) {
                            coins.add(parts[++i].toUpperCase());
                        }
                    }
                }
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            this.errorSintaxis = true;
            this.mensajeError = "Error de sintaxis: Te ha faltado indicar un valor después de una bandera.";
        }
    }

    // --- Getters ---
    public boolean isReal() { return isReal; }
    public boolean isVirtual() { return isVirtual; }
    public String getEstrategia() { return estrategia; }
    public String getModelo() { return modelo; }
    public String getTimeframe() { return timeframe; }
    public List<String> getCoins() { return coins; }
    
    public boolean hasErrorSintaxis() { return errorSintaxis; }
    public String getMensajeError() { return mensajeError; }
}