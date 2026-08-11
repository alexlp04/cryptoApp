package com.bottrading.backtesting.infrastructure;

import com.bottrading.shared.exceptions.FileOperationException;
import com.bottrading.shared.utils.PathConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Utilidades compartidas para operaciones CSV.
 * Parsing, escaping, gestión de carpetas.
 * 
 * Responsabilidad única: operaciones técnicas de CSV.
 */
@Slf4j
@Service
public class CsvUtilities {

    /**
     * Obtiene o crea la carpeta de una estrategia.
     */
    public Path getCarpetaEstrategia(String nombreEstrategia) throws FileOperationException {
        return PathConfig.getCarpetaEstrategia(nombreEstrategia);
    }

    /**
     * Parsea una línea CSV respetando comillas y escaping.
     */
    public String[] parseCsvLine(String line) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        int i = 0;

        while (i < line.length()) {
            char c = line.charAt(i);

            if (handleQuoteCharacter(c, i, line, inQuotes, current)) {
                if (line.charAt(i) == '\"' && i + 1 < line.length() && line.charAt(i + 1) == '\"') {
                    i++;  // Salta comilla doble
                }
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                tokens.add(trimQuotedString(current.toString()));
                current = new StringBuilder();
            } else {
                current.append(c);
            }
            i++;
        }

        tokens.add(trimQuotedString(current.toString()));
        return tokens.toArray(new String[0]);
    }

    /**
     * Determina si el carácter es una comilla válida para procesar.
     */
    private boolean handleQuoteCharacter(char c, int index, String line, boolean inQuotes, StringBuilder current) {
        if (c != '\"') {
            return false;
        }
        if (inQuotes && index + 1 < line.length() && line.charAt(index + 1) == '\"') {
            current.append('\"');
            return true;
        }
        return !inQuotes && current.toString().trim().isEmpty();
    }

    /**
     * Limpia una cadena entrecomillada.
     */
    private String trimQuotedString(String token) {
        token = token.trim();
        if (token.startsWith("\"") && token.endsWith("\"") && token.length() >= 2) {
            token = token.substring(1, token.length() - 1);
        }
        return token;
    }

    /**
     * Escapa un valor para CSV.
     * Si el valor contiene comas, comillas o saltos de línea,
     * lo envuelve en comillas dobles y escapa las comillas internas.
     */
    public String escapeCsv(String data) {
        if (data == null) {
            return "";
        }
        if (data.contains(",") || data.contains("\"") || data.contains("\n")) {
            // Reemplazamos comillas internas por dobles comillas (estándar CSV)
            data = data.replace("\"", "\"\"");
            return "\"" + data + "\"";
        }
        return data;
    }
}
