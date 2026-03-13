package com.bottrading.services;

import com.bottrading.exceptions.DataFetchException;
import com.bottrading.repositories.IndicadorRepository;
import com.bottrading.repositories.VelaRepository;
import com.bottrading.utils.AppConstants;
import com.bottrading.utils.ConsoleLoader;
import com.bottrading.utils.DataSerializationUtils;
import com.bottrading.utils.PathConfig;
import com.bottrading.beans.VelaDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.io.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
    private final ExecutorService executor;

    private static final int BATCH_INSERT_SIZE = 50000;
    private static final int TIMEOUT_SECONDS = 30;
    private static final int MAX_RETRIES = 3;

    public FetchService(VelaRepository velaRepo, IndicadorRepository indicadorRepo, JdbcTemplate jdbcTemplate) {
        this.velaRepo = velaRepo;
        this.indicadorRepo = indicadorRepo;
        this.jdbcTemplate = jdbcTemplate;
        this.executor = Executors.newFixedThreadPool(4);
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
     * Ejecuta el script extractor de Python y persiste los datos usando MessagePack streaming.
     * Implementa timeouts, retries automáticos y manejo de errores robusto.
     */
    private void callPythonAndSave(String symbol, String interval, Long fromTimestamp) {
        int retries = 0;
        
        while (retries < MAX_RETRIES) {
            Process process = null;
            try {
                ConsoleLoader.getInstance().startSpinner("Sincronizando velas con MessagePack para " + symbol);
                
                ProcessBuilder pb = new ProcessBuilder(AppConstants.PYTHON_EXECUTABLE, PathConfig.FETCHER_PATH, symbol, interval);
                if (fromTimestamp != null) {
                    pb.command().add(String.valueOf(fromTimestamp));
                }

                process = pb.start();
                final Process pRef = process;

                // Thread para leer stderr
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

                // Esperar con timeout
                if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new DataFetchException("Timeout en fetch Python tras " + TIMEOUT_SECONDS + "s");
                }

                int exitCode = process.exitValue();
                if (exitCode != 0) {
                    ConsoleLoader.getInstance().stopClear();
                    log.error("Script Python exited with code {}", exitCode);
                    throw new DataFetchException("Script Python failed with code " + exitCode);
                }

                ConsoleLoader.getInstance().stop("✅ Sincronización completa para " + symbol + ". Velas: " + totalGuardadas);
                log.info("Fetch completado para {}. Total guardado: {}", symbol, totalGuardadas);
                return;

            } catch (InterruptedException e) {
                retries++;
                ConsoleLoader.getInstance().stopClear();
                Thread.currentThread().interrupt();
                if (process != null) process.destroyForcibly();
                log.warn("Reintento {} para fetch (InterruptedException): {}", retries, e.getMessage());
                if (retries >= MAX_RETRIES) {
                    throw new DataFetchException("Fallo tras " + MAX_RETRIES + " reintentos", e);
                }
            } catch (Exception e) {
                retries++;
                if (process != null) process.destroyForcibly();
                log.warn("Reintento {} para fetch: {}", retries, e.getMessage());
                if (retries >= MAX_RETRIES) {
                    ConsoleLoader.getInstance().stopClear();
                    log.error("Error crítico en FetchService tras {} reintentos: {}", MAX_RETRIES, e.getMessage(), e);
                    throw new DataFetchException("Data synchronization failed", e);
                }
            }
        }
    }

    /**
     * Lee velas desde Python en streaming con formato MessagePack e inserta en BD con batch.
     * Reemplaza el anterior formato TSV con serialización binaria eficiente.
     */
    private int leerVelasEnStreamingYGuardar(Process process, String symbol, String interval) throws IOException {
        int totalGuardadas = 0;
        long tiempoInicio = System.currentTimeMillis();
        List<Object[]> batch = new ArrayList<>(BATCH_INSERT_SIZE);

        try (InputStream in = process.getInputStream()) {
            // Deserializar MessagePack stream
            List<VelaDTO> velasDTO = DataSerializationUtils.deserializeVelasFromStream(in, false);
            
            if (velasDTO == null || velasDTO.isEmpty()) {
                log.warn("No se recibieron velas de Python para {}", symbol);
                return 0;
            }

            for (VelaDTO dto : velasDTO) {
                Object[] datos = new Object[] {
                    dto.getOpenTime(),              // openTime
                    new BigDecimal(dto.getOpen()),  // open
                    new BigDecimal(dto.getHigh()),  // high
                    new BigDecimal(dto.getLow()),   // low
                    new BigDecimal(dto.getClose()), // close
                    new BigDecimal(dto.getVolume()), // volume
                    dto.getCloseTime(),             // closeTime
                    new BigDecimal(dto.getQuoteVolume()), // quoteVolume
                    dto.getTrades(),                // trades
                    new BigDecimal(dto.getTakerBaseVolume()), // takerBaseVolume
                    new BigDecimal(dto.getTakerQuoteVolume()), // takerQuoteVolume
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
            }

            // Insertar batch final si no está vacío
            if (!batch.isEmpty()) {
                int insertadas = guardarBatchVelas(batch);
                totalGuardadas += insertadas;
            }
        }

        long tiempoTotal = System.currentTimeMillis() - tiempoInicio;
        double velocidadMedia = totalGuardadas > 0 ? totalGuardadas / (tiempoTotal / 1000.0) : 0;
        log.info("Lectura MessagePack completada: {} registros guardados en {} ms ({} registros/seg)", 
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

    @PreDestroy
    public void shutdown() {
        log.info("Iniciando shutdown de ExecutorService en FetchService");
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("ExecutorService no terminó en tiempo. Forzando shutdown");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            log.error("Interrupción durante shutdown graceful. Forzando shutdown");
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("Shutdown de ExecutorService completado");
    }
}