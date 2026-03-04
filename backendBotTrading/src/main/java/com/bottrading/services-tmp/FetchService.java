package com.bottrading.services;

import com.bottrading.beans.Vela;
import com.bottrading.beans.VelaDTO;
import com.bottrading.exceptions.DataFetchException;
import com.bottrading.repositories.IndicadorRepository;
import com.bottrading.repositories.VelaRepository;
import com.bottrading.utils.ConsoleLoader;
import com.bottrading.utils.PathConfig;
import com.bottrading.utils.SafeParser;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;
import java.util.stream.Collectors;

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

    private final Gson gson = new Gson();

    @Autowired
    public FetchService(VelaRepository velaRepo, IndicadorRepository indicadorRepo) {
        this.velaRepo = velaRepo;
        this.indicadorRepo = indicadorRepo;
    }

    /**
     * Coordina la descarga incremental de datos de mercado.
     * Verifica en la base de datos la última marca de tiempo (timestamp) registrada
     * y solicita al script de Python solo los datos faltantes para evitar
     * duplicados.
     *
     * @param symbol   El par de trading (ej: "BTCUSDT").
     * @param interval El intervalo de la vela (ej: "1m", "1h").
     */
    public void fetch(String symbol, String interval) {
        log.info("Comprobando datos para: {} [{}]", symbol, interval);

        Long lastTimestamp = velaRepo.findMaxOpenTimeBySymbolAndInterval(symbol, interval);

        if (lastTimestamp == null) {
            log.info("No hay datos previos. Iniciando descarga completa...");
            ConsoleLoader.getInstance().startDots();

            callPythonAndSave(symbol, interval, null);

        } else {
            long now = System.currentTimeMillis();
            if (now - lastTimestamp > getIntervalMillis(interval)) {
                log.info("Datos desactualizados. Descargando desde: {}", lastTimestamp);
                ConsoleLoader.getInstance().startDots();
                callPythonAndSave(symbol, interval, lastTimestamp + 1);
                ConsoleLoader.getInstance().stop();

            } else {
                log.info("Los datos ya están al día.");
            }
        }
    }

    /**
     * Coordina la descarga incremental inteligente.
     * Revisa la BD y decide si descargar los X días completos o solo el hueco
     * restante.
     * * @return El timestamp (en milisegundos) desde el que se ha empezado a
     * descargar.
     */
    public long fetchIncremental(String symbol, String interval, int dias, long now) {
        long millisPerDay = 24L * 60L * 60L * 1000L;
        long targetTimestamp = now - dias * millisPerDay;

        Long lastTimestamp = velaRepo.findMaxOpenTimeBySymbolAndInterval(symbol, interval);
        long fetchFromTimestamp;

        if (lastTimestamp != null && lastTimestamp > targetTimestamp) {
            // Ya hay historial. Descargamos desde la última vela registrada
            // (Sobreescribimos esa última vela por si la anterior vez se guardó incompleta)
            fetchFromTimestamp = lastTimestamp;
            log.info("Historial detectado. Descargando solo nuevas velas desde: {}", lastTimestamp);
        } else {
            // Historial vacío o insuficiente
            fetchFromTimestamp = targetTimestamp;
            log.info("Historial incompleto. Descargando {} días completos desde: {}", dias, targetTimestamp);
        }

        log.info("Limpiando datos residuales desde el punto de anclaje...");
        ConsoleLoader.getInstance().startDots();

        // Borramos SOLO de la fecha de anclaje en adelante. Lo viejo ni se toca.
        indicadorRepo.deleteByVelaSymbolAndIntervalAndOpenTimeGreaterThanEqual(symbol, interval, fetchFromTimestamp);
        velaRepo.deleteBySymbolAndIntervalAndOpenTimeGreaterThanEqual(symbol, interval, fetchFromTimestamp);

        // Llamamos a Python
        callPythonAndSave(symbol, interval, fetchFromTimestamp);
        ConsoleLoader.getInstance().stop();

        return fetchFromTimestamp; // Devolvemos el dato para que el MarketDataService sepa qué hacer
    }

    /**
     * Ejecuta el script extractor de Python y persiste los datos recibidos en una
     * transacción por lotes.
     * Este método lee la salida JSON directamente del flujo del proceso (stream)
     * para minimizar
     * la sobrecarga de memoria al procesar grandes conjuntos de datos.
     *
     * @param symbol        El par de trading.
     * @param interval      El intervalo de tiempo.
     * @param fromTimestamp El timestamp de inicio (opcional, null para descarga
     *                      completa).
     */
    private void callPythonAndSave(String symbol, String interval, Long fromTimestamp) {
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("python3", PathConfig.FETCHER_PATH, symbol, interval);

            if (fromTimestamp != null) {
                pb.command().add(String.valueOf(fromTimestamp));
            }

            process = pb.start();

            // Hilo para capturar errores
            final Process pRef = process;
            new Thread(() -> {
                try (BufferedReader err = new BufferedReader(new InputStreamReader(pRef.getErrorStream()))) {
                    String line;
                    while ((line = err.readLine()) != null) {
                        log.error("PYTHON ERROR: {}", line);
                    }
                } catch (Exception e) {
                    log.error("Error leyendo STDERR: {}", e.getMessage());
                }
            }).start();

            // --- LECTURA POR LOTES ---
            int totalGuardadas = 0;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String lineaJson;

                // Leemos línea por línea. Cada línea es un lote de 10.000 velas de Python.
                while ((lineaJson = reader.readLine()) != null) {
                    // Convertimos esta línea específica en una lista de DTOs
                    List<VelaDTO> loteDTOs = gson.fromJson(lineaJson, new TypeToken<List<VelaDTO>>() {
                    }.getType());

                    if (loteDTOs != null && !loteDTOs.isEmpty()) {
                        List<Vela> entidades = loteDTOs.stream()
                                .map(dto -> mapToEntity(dto, symbol, interval))
                                .collect(Collectors.toList());

                        // Guardamos el lote en la base de datos
                        velaRepo.saveAll(entidades);

                        totalGuardadas += entidades.size();
                        log.info("Lote guardado. Total acumulado: {} velas de {}.", totalGuardadas, symbol);

                        entidades.clear();
                        loteDTOs.clear();
                    }
                }
            }

            int exitCode = process.waitFor();

            if (exitCode != 0) {
                log.error("Script Python terminó con error (código {})", exitCode);
                throw new DataFetchException("Script Python falló con código " + exitCode);
            }

            log.info("Sincronización completa para {}. Velas totales guardadas: {}", symbol, totalGuardadas);
            ConsoleLoader.getInstance().stop();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null)
                process.destroy();
            log.warn("La sincronización fue interrumpida para {}", symbol);
            throw new DataFetchException("Hilo interrumpido durante la sincronización", e);
        } catch (Exception e) {
            log.error("Error crítico en FetchService: {}", e.getMessage(), e);
            throw new DataFetchException("Fallo en sincronización de datos", e);
        }
    }

    /**
     * Mapea un Objeto de Transferencia de Datos (DTO) a una Entidad JPA.
     * Realiza una conversión segura de String a BigDecimal para garantizar la
     * precisión financiera.
     *
     * @param dto      El objeto de datos crudos del JSON.
     * @param symbol   El símbolo del mercado.
     * @param interval El intervalo de tiempo.
     * @return Una entidad Vela lista para persistir.
     */
    private Vela mapToEntity(VelaDTO dto, String symbol, String interval) {
        Vela v = new Vela();
        v.setSymbol(SafeParser.toString(symbol));
        v.setInterval(SafeParser.toString(interval));
        v.setOpenTime(SafeParser.toLong(dto.getOpenTime()));
        v.setCloseTime(SafeParser.toLong(dto.getCloseTime()));

        v.setOpen(SafeParser.toBigDecimal(dto.getOpen()));
        v.setHigh(SafeParser.toBigDecimal(dto.getHigh()));
        v.setLow(SafeParser.toBigDecimal(dto.getLow()));
        v.setClose(SafeParser.toBigDecimal(dto.getClose()));
        v.setVolume(SafeParser.toBigDecimal(dto.getVolume()));
        v.setQuoteVolume(SafeParser.toBigDecimal(dto.getQuoteVolume()));
        v.setTakerBaseVolume(SafeParser.toBigDecimal(dto.getTakerBaseVolume()));
        v.setTakerQuoteVolume(SafeParser.toBigDecimal(dto.getTakerQuoteVolume()));

        v.setTrades(SafeParser.toInt(dto.getTrades()));
        return v;
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