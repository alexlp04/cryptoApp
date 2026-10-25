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
        boolean quoted = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"'); // "" dentro de comillas = comilla literal
                    i++;
                } else if (c == '"') {
                    inQuotes = false;    // comilla de cierre: antes se quedaba en modo comillas hasta el final
                } else {
                    current.append(c);
                }
            } else if (c == '"' && !quoted && current.toString().trim().isEmpty()) {
                inQuotes = true;
                quoted = true;
                current.setLength(0);
            } else if (c == ',') {
                tokens.add(quoted ? current.toString() : current.toString().trim());
                current.setLength(0);
                quoted = false;
            } else if (!quoted || !Character.isWhitespace(c)) {
                current.append(c);
            }
        }

        tokens.add(quoted ? current.toString() : current.toString().trim());
        return tokens.toArray(new String[0]);
    }

    /**
     * Determina si el carácter es una comilla válida para procesar.
     */


    /**
     * Limpia una cadena entrecomillada.
     */


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
