package com.bottrading.trading.infrastructure.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.springframework.stereotype.Component;

import com.bottrading.shared.exceptions.FileOperationException;
import com.bottrading.shared.utils.PathConfig;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class TradingCsvUtilities {

    public Path getCarpetaEstrategia(String nombreEstrategia) {
        try {
            Path path = Paths.get(PathConfig.RESULTS_DIR, nombreEstrategia);
            if (!Files.exists(path)) {
                Files.createDirectories(path);
                log.debug("Carpeta creada: {}", path);
            }
            return path;
        } catch (IOException e) {
            throw new FileOperationException("Error al crear carpeta de estrategia: " + e.getMessage(), e);
        }
    }
}
