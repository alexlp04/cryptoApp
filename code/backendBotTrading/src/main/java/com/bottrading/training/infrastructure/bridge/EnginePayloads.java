package com.bottrading.training.infrastructure.bridge;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.bottrading.market.domain.Vela;

/** Piezas del payload IPC que comparten los motores de entrenamiento y de optimización. */
final class EnginePayloads {

    private EnginePayloads() {
    }

    /** Primer símbolo del mapa (el motor entrena con las velas de todos pero nombra el modelo con este). */
    static String simboloPrincipal(Map<String, List<Vela>> symbolCandles) {
        return symbolCandles.keySet().stream().findFirst().orElse("UNKNOWN");
    }

    /**
     * Filas OHLCV de todos los símbolos, en el orden del mapa.
     * shortcut: un Map por vela; para 1m x 1 año (~525k filas) convendría el formato columnar.
     */
    static List<Map<String, Object>> serializarVelas(Map<String, List<Vela>> symbolCandles) {
        List<Map<String, Object>> dataset = new ArrayList<>();
        for (List<Vela> velas : symbolCandles.values()) {
            for (Vela vela : velas) {
                Map<String, Object> row = new HashMap<>();
                row.put("timestamp", vela.getOpenTime());
                row.put("open", vela.getOpen());
                row.put("high", vela.getHigh());
                row.put("low", vela.getLow());
                row.put("close", vela.getClose());
                row.put("volume", vela.getVolume());
                dataset.add(row);
            }
        }
        return dataset;
    }

    /** Nombre del módulo de la estrategia: el fichero sin ruta ni extensión .py. */
    static String nombreEstrategia(String strategyPath) {
        Path path = Paths.get(strategyPath).getFileName();
        String fileName = path == null ? strategyPath : path.toString();
        return fileName.endsWith(".py") ? fileName.substring(0, fileName.length() - 3) : fileName;
    }
}
