package com.bottrading.services;

import com.bottrading.exceptions.DataFetchException;
import com.bottrading.repositories.IndicadorRepository;
import com.bottrading.repositories.VelaRepository;
import com.bottrading.utils.AppConstants;
import com.bottrading.utils.ConsoleLoader;
import com.bottrading.utils.PathConfig;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Servicio encargado de la sincronización de datos de mercado
 * (Velas/Candlesticks)
 * entre un script externo de Python y la base de datos interna.
 * Gestiona descargas incrementales para optimizar el ancho de banda y el
 * almacenamiento.
 */
@Slf4j
@Service
public class FetchService {

    private final VelaRepository velaRepo;
    private final IndicadorRepository indicadorRepo;
    private final JdbcTemplate jdbcTemplate;

    private static final int BATCH_INSERT_SIZE = 50000;

    @Autowired
    public FetchService(VelaRepository velaRepo, IndicadorRepository indicadorRepo, JdbcTemplate jdbcTemplate) {
        this.velaRepo = velaRepo;
        this.indicadorRepo = indicadorRepo;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Coordina la descarga incremental de datos de mercado.
     */
    public void fetch(String symbol, String interval) {
        log.info("Comprobando datos para: {} [{}]", symbol, interval);
        ConsoleLoader.getInstance().startDots("Verificando historial local para " + symbol);

        Long lastTimestamp = velaRepo.findMaxOpenTimeBySymbolAndInterval(symbol, interval);

        if (lastTimestamp == null) {
            ConsoleLoader.getInstance().stopClear();
            log.info("No hay datos previos. Iniciando descarga completa...");
            callPythonAndSave(symbol, interval, null);

        } else {
            long now = System.currentTimeMillis();
            if (now - lastTimestamp > getIntervalMillis(interval)) {
                ConsoleLoader.getInstance().stopClear();
                log.info("Datos desactualizados. Descargando desde: {}", lastTimestamp);
                callPythonAndSave(symbol, interval, lastTimestamp + 1);
            } else {
                ConsoleLoader.getInstance().stop("✅ Historial de " + symbol + " ya está actualizado.");
                log.info("Los datos ya están al día.");
            }
        }
    }

    /**
     * Coordina la descarga incremental inteligente.
     */
    public long fetchIncremental(String symbol, String interval, int dias, long now) {
        long millisPerDay = 24L * 60L * 60L * 1000L;
        long targetTimestamp = now - dias * millisPerDay;

        ConsoleLoader.getInstance().startDots("Analizando brechas de datos para " + symbol);
        Long lastTimestamp = velaRepo.findMaxOpenTimeBySymbolAndInterval(symbol, interval);
        long fetchFromTimestamp;

        if (lastTimestamp != null && lastTimestamp > targetTimestamp) {
            fetchFromTimestamp = lastTimestamp;
            log.info("Historial detectado. Descargando solo nuevas velas desde: {}", lastTimestamp);
        } else {
            fetchFromTimestamp = targetTimestamp;
            log.info("Historial incompleto. Descargando {} días completos desde: {}", dias, targetTimestamp);
        }

        ConsoleLoader.getInstance().stopClear();
        ConsoleLoader.getInstance().startSpinner("Limpiando datos residuales de " + symbol);
        
        indicadorRepo.deleteByVelaSymbolAndIntervalAndOpenTimeGreaterThanEqual(symbol, interval, fetchFromTimestamp);
        velaRepo.deleteBySymbolAndIntervalAndOpenTimeGreaterThanEqual(symbol, interval, fetchFromTimestamp);

        ConsoleLoader.getInstance().stopClear();
        
        callPythonAndSave(symbol, interval, fetchFromTimestamp);

        return fetchFromTimestamp;
    }

    /**
     * Ejecuta el script extractor de Python y persiste los datos en streaming.
     */
    private void callPythonAndSave(String symbol, String interval, Long fromTimestamp) {
        Process process = null;
        try {
            ConsoleLoader.getInstance().startSpinner("Sincronizando velas de mercado para " + symbol + " (Streaming)");
            
            ProcessBuilder pb = new ProcessBuilder(AppConstants.PYTHON_EXECUTABLE, PathConfig.FETCHER_PATH, symbol,
                    interval);

            if (fromTimestamp != null) {
                pb.command().add(String.valueOf(fromTimestamp));
            }

            process = pb.start();

            final Process pRef = process;
            new Thread(() -> {
                try (BufferedReader err = new BufferedReader(new InputStreamReader(pRef.getErrorStream()))) {
                    String line;
                    while ((line = err.readLine()) != null) {
                        log.error("Python stderr: {}", line);
                    }
                } catch (Exception e) {
                    log.error("Error reading stderr: {}", e.getMessage());
                }
            }).start();

            int totalGuardadas = leerVelasEnStreamingYGuardar(process, symbol, interval);

            int exitCode = process.waitFor();

            if (exitCode != 0) {
                ConsoleLoader.getInstance().stopClear();
                log.error("Script Python exited with code {}", exitCode);
                throw new DataFetchException("Script Python failed with code " + exitCode);
            }

            ConsoleLoader.getInstance().stop("✅ Sincronización completa para " + symbol + ". Velas procesadas: " + totalGuardadas);
            log.info("Fetch completed for {}. Total saved: {} candles", symbol, totalGuardadas);

        } catch (InterruptedException e) {
            ConsoleLoader.getInstance().stopClear();
            Thread.currentThread().interrupt();
            if (process != null)
                process.destroy();
            log.warn("Fetch interrupted for {}", symbol);
            throw new DataFetchException("Sync thread interrupted", e);
        } catch (Exception e) {
            ConsoleLoader.getInstance().stopClear();
            log.error("Critical error in FetchService: {}", e.getMessage(), e);
            throw new DataFetchException("Data synchronization failed", e);
        }
    }

    /**
     * Lee velas desde Python en streaming (formato TSV) e inserta en BD con batch.
     * La lectura y persistencia ocurren en paralelo para maximizar rendimiento.
     */
    private int leerVelasEnStreamingYGuardar(Process process, String symbol, String interval) throws IOException {
        int totalGuardadas = 0;
        int lineasLeidas = 0;
        long tiempoInicio = System.currentTimeMillis();
        List<Object[]> batch = new ArrayList<>(BATCH_INSERT_SIZE);

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8),
                512 * 1024)) {

            String linea;
            while ((linea = reader.readLine()) != null) {
                if (linea.trim().isEmpty())
                    continue;

                lineasLeidas++;

                try {
                    // Esperamos exactamente 12 campos TSV del script Python
                    String[] campos = linea.split("\t", 12);
                    if (campos.length < 12)
                        continue;

                    Object[] datos = new Object[] {
                            Long.parseLong(campos[0]),            // openTime
                            new BigDecimal(campos[1]),             // open
                            new BigDecimal(campos[2]),             // high
                            new BigDecimal(campos[3]),             // low
                            new BigDecimal(campos[4]),             // close
                            new BigDecimal(campos[5]),             // volume
                            Long.parseLong(campos[6]),            // closeTime
                            new BigDecimal(campos[7]),             // quoteVolume
                            Integer.parseInt(campos[8]),           // trades
                            new BigDecimal(campos[9]),             // takerBaseVolume
                            new BigDecimal(campos[10]),            // takerQuoteVolume
                            symbol,
                            interval
                    };

                    batch.add(datos);

                    // Insertar cuando alcanzamos el tamaño del batch
                    if (batch.size() >= BATCH_INSERT_SIZE) {
                        int insertadas = guardarBatchVelas(batch);
                        totalGuardadas += insertadas;
                        if (totalGuardadas % 100000 == 0) {
                            long tiempoTranscurrido = System.currentTimeMillis() - tiempoInicio;
                            double velocidad = totalGuardadas / (tiempoTranscurrido / 1000.0);
                            log.info("Progreso: {} registros guardados ({} registros/seg)", 
                                     totalGuardadas, String.format("%.0f", velocidad));
                        }
                        batch.clear();
                    }

                } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
                    log.debug("Línea malformada omitida (línea {}): {}", lineasLeidas, linea);
                }
            }

            // Insertar batch final si no está vacío
            if (!batch.isEmpty()) {
                int insertadas = guardarBatchVelas(batch);
                totalGuardadas += insertadas;
            }
        }

        long tiempoTotal = System.currentTimeMillis() - tiempoInicio;
        double velocidadMedia = totalGuardadas > 0 ? totalGuardadas / (tiempoTotal / 1000.0) : 0;
        log.info("Lectura streaming completada: {} registros guardados en {} ms ({} registros/seg)", 
                 totalGuardadas, tiempoTotal, String.format("%.0f", velocidadMedia));

        return totalGuardadas;
    }

    /**
     * Inserta batch de velas directamente en BD usando JDBC sin ORM.
     * ON DUPLICATE KEY UPDATE maneja solapamiento automáticamente.
     */
    private int guardarBatchVelas(List<Object[]> batch) {
        if (batch.isEmpty()) {
            return 0;
        }
        
        String sql = "INSERT INTO vela (open_time, open, high, low, close, volume, close_time, quote_volume, trades, taker_base_volume, taker_quote_volume, symbol, time_interval) " +
                     "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?) " +
                     "ON DUPLICATE KEY UPDATE " +
                     "  close = VALUES(close), volume = VALUES(volume)";

        try {
            int[] resultados = jdbcTemplate.batchUpdate(sql, batch);
            int insertadas = (int) Arrays.stream(resultados).filter(r -> r > 0).count();
            log.debug("Batch inserted: {} records", batch.size());
            return insertadas;
        } catch (Exception e) {
            log.error("Error inserting batch of {} records: {}", batch.size(), e.getMessage());
            throw new DataFetchException("Batch insert failed", e);
        }
    }

    public static long getIntervalMillis(String interval) {
        return switch (interval) {
            case "1m" -> 60_000L;
            case "5m" -> 300_000L;
            case "15m" -> 900_000L;
            case "1h" -> 3_600_000L;
            case "4h" -> 14_400_000L;
            case "1d" -> 86_400_000L;
            default -> 60_000L;
        };
    }
}