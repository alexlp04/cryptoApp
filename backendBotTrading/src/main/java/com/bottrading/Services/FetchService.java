package com.bottrading.services;

import com.bottrading.beans.Vela;
import com.bottrading.beans.VelaDTO;
import com.bottrading.repositories.VelaRepository;
import com.bottrading.utils.PathConfig;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
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

    private final Gson gson = new Gson();

    @Autowired
    public FetchService(VelaRepository velaRepo) {
        this.velaRepo = velaRepo;
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
        log.info("🔍 Comprobando datos para: {} [{}]", symbol, interval);

        Long lastTimestamp = velaRepo.findMaxOpenTimeBySymbolAndInterval(symbol, interval);

        if (lastTimestamp == null) {
            log.info("No hay datos previos. Iniciando descarga completa...");
            callPythonAndSave(symbol, interval, null);
        } else {
            long now = System.currentTimeMillis();
            if (now - lastTimestamp > getIntervalMillis(interval)) {
                log.info("⏳ Datos desactualizados. Descargando desde: {}", lastTimestamp);
                callPythonAndSave(symbol, interval, lastTimestamp + 1); // +1 para evitar solapamiento
            } else {
                log.info("Los datos ya están al día.");
            }
        }
    }

    /**
     * Ejecuta el script extractor de Python y persiste los datos recibidos en una
     * transacción por lotes.
     * <p>
     * Este método lee la salida JSON directamente del flujo del proceso (stream)
     * para minimizar
     * la sobrecarga de memoria al procesar grandes conjuntos de datos.
     * </p>
     *
     * @param symbol        El par de trading.
     * @param interval      El intervalo de tiempo.
     * @param fromTimestamp El timestamp de inicio (opcional, null para descarga
     *                      completa).
     */
    private void callPythonAndSave(String symbol, String interval, Long fromTimestamp) {
        Process process = null; // Necesario para el scope del finally/catch
        try {
            ProcessBuilder pb = new ProcessBuilder("python", PathConfig.FETCHER_PATH, symbol, interval);
            if (fromTimestamp != null) {
                pb.command().add(String.valueOf(fromTimestamp));
            }

            process = pb.start();

            // Hilo para capturar errores (igual que antes)
            final Process pRef = process; // Variable final efectiva para lambda
            new Thread(() -> {
                try (BufferedReader err = new BufferedReader(new InputStreamReader(pRef.getErrorStream()))) {
                    String line;
                    while ((line = err.readLine()) != null) {
                        log.error("🐍 PYTHON ERROR: {}", line);
                    }
                } catch (Exception e) {
                    log.error("Error leyendo STDERR: {}", e.getMessage());
                }
            }).start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                List<VelaDTO> dtoList = gson.fromJson(reader, new TypeToken<List<VelaDTO>>() {
                }.getType());

                if (dtoList != null && !dtoList.isEmpty()) {
                    List<Vela> entidades = dtoList.stream()
                            .map(dto -> mapToEntity(dto, symbol, interval))
                            .collect(Collectors.toList());
                    velaRepo.saveAll(entidades);
                    log.info("Guardadas {} velas.", entidades.size());
                }
            }

            // Aquí es donde puede saltar la InterruptedException
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                log.error("Script Python terminó con error (código {})", exitCode);
                throw new RuntimeException("Script Python falló con código " + exitCode);
            }

        } catch (InterruptedException e) {
            // 1. RESTAURAR EL ESTADO DE INTERRUPCIÓN (Cumple SonarQube)
            Thread.currentThread().interrupt();

            // 2. LIMPIEZA: Matar el proceso huérfano si nos interrumpen
            if (process != null)
                process.destroy();

            log.warn("La sincronización fue interrumpida para {}", symbol);
            throw new RuntimeException("Hilo interrumpido durante la sincronización", e);

        } catch (Exception e) {
            // Captura genérica para IOException, RuntimeException, etc.
            log.error("Error crítico en FetchService: {}", e.getMessage(), e);
            throw new RuntimeException("Fallo en sincronización de datos", e);
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
        v.setSymbol(symbol);
        v.setInterval(interval);
        v.setOpenTime(dto.open_time);
        v.setCloseTime(dto.close_time);

        v.setOpen(new BigDecimal(dto.open));
        v.setHigh(new BigDecimal(dto.high));
        v.setLow(new BigDecimal(dto.low));
        v.setClose(new BigDecimal(dto.close));
        v.setVolume(new BigDecimal(dto.volume));
        v.setQuoteVolume(new BigDecimal(dto.quote_volume));
        v.setTakerBaseVolume(new BigDecimal(dto.taker_base_volume));
        v.setTakerQuoteVolume(new BigDecimal(dto.taker_quote_volume));

        v.setTrades(dto.trades);
        return v;
    }

    private long getIntervalMillis(String interval) {
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