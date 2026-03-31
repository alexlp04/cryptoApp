package com.bottrading.infrastructure.persistence;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Gestor de limpieza de artefactos de backtesting.
 * Elimina archivos de backtest previos cuando sea necesario.
 * 
 * Responsabilidad única: limpieza de archivos de backtesting.
 */
@Slf4j
@Service
public class BacktestArtifactsCleaner {

    private final CsvUtilities csvUtils;

    public BacktestArtifactsCleaner(CsvUtilities csvUtils) {
        this.csvUtils = csvUtils;
    }

    /**
     * Verifica y limpia la carpeta de una estrategia.
     * Si limpiarBacktestsPrevios es true, elimina archivos de backtest anteriores.
     */
    public void verificarYLimpiarCarpetaEstrategia(String nombreEstrategia, boolean limpiarBacktestsPrevios) {
        Path carpeta = Paths.get(com.bottrading.utils.PathConfig.RESULTS_DIR, nombreEstrategia);

        if (!Files.exists(carpeta)) {
            return;
        }

        if (limpiarBacktestsPrevios) {
            limpiarArchivosBacktest(carpeta, nombreEstrategia);
        } else {
            log.info("Manteniendo archivos de backtest previos para {}", nombreEstrategia);
        }
    }

    /**
     * Elimina todos los archivos de backtest en una carpeta.
     */
    private void limpiarArchivosBacktest(Path carpeta, String nombreEstrategia) {
        try (var stream = Files.list(carpeta)) {
            stream.filter(path -> path.getFileName().toString().contains("backtest"))
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                            log.debug("Archivo de backtest eliminado: {}", path.getFileName());
                        } catch (IOException e) {
                            log.error("No se pudo borrar: {}", path.getFileName());
                        }
                    });
            log.info("Archivos de backtest antiguos eliminados de: {}", nombreEstrategia);
        } catch (IOException e) {
            log.error("Error al acceder a la carpeta {}: {}", carpeta, e.getMessage());
        }
    }
}
