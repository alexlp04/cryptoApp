package com.bottrading.services;

import com.bottrading.beans.Vela;
import com.bottrading.beans.VelaDTO;
import com.bottrading.repositories.VelaRepository;
import com.bottrading.utils.PathConfig;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Servicio encargado de la sincronización de datos de mercado (Velas/Candlesticks)
 * entre un script externo de Python y la base de datos interna.
 * Gestiona descargas incrementales para optimizar el ancho de banda y el almacenamiento.
 */
@Service
public class FetchService {

    @Autowired
    private VelaRepository velaRepo;

    private final Gson gson = new Gson();

    /**
     * Coordina la descarga incremental de datos de mercado.
     * Verifica en la base de datos la última marca de tiempo (timestamp) registrada
     * y solicita al script de Python solo los datos faltantes para evitar duplicados.
     *
     * @param symbol   El par de trading (ej: "BTCUSDT").
     * @param interval El intervalo de la vela (ej: "1m", "1h").
     */
    public void fetch(String symbol, String interval) {
        System.out.println("🔍 Comprobando datos para: " + symbol + " [" + interval + "]");

        Long lastTimestamp = velaRepo.findMaxOpenTimeBySymbolAndInterval(symbol, interval);

        if (lastTimestamp == null) {
            System.out.println("No hay datos previos. Iniciando descarga completa...");
            callPythonAndSave(symbol, interval, null);
        } else {
            long now = System.currentTimeMillis();
            if (now - lastTimestamp > getIntervalMillis(interval)) {
                System.out.println("⏳ Datos desactualizados. Descargando desde: " + lastTimestamp);
                callPythonAndSave(symbol, interval, lastTimestamp);
            } else {
                System.out.println("Los datos ya están al día.");
            }
        }
    }

    /**
     * Ejecuta el script extractor de Python y persiste los datos recibidos en una transacción por lotes.
     * <p>
     * Este método lee la salida JSON directamente del flujo del proceso (stream) para minimizar
     * la sobrecarga de memoria al procesar grandes conjuntos de datos.
     * </p>
     *
     * @param symbol        El par de trading.
     * @param interval      El intervalo de tiempo.
     * @param fromTimestamp El timestamp de inicio (opcional, null para descarga completa).
     */
    @Transactional
    private void callPythonAndSave(String symbol, String interval, Long fromTimestamp) {
        try {
            ProcessBuilder pb = new ProcessBuilder("python3", PathConfig.FETCHER_PATH, symbol, interval);
            if (fromTimestamp != null) {
                pb.command().add(String.valueOf(fromTimestamp));
            }

            Process process = pb.start();

            // Hilo para capturar e imprimir errores de Python en tiempo real (debug)
            new Thread(() -> {
                try (BufferedReader err = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                    String line;
                    while ((line = err.readLine()) != null) {
                        System.err.println("🐍 PYTHON ERROR: " + line);
                    }
                } catch (Exception ignored) {}
            }).start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                List<VelaDTO> dtoList = gson.fromJson(reader, new TypeToken<List<VelaDTO>>() {}.getType());

                if (dtoList == null || dtoList.isEmpty()) {
                    System.out.println("No se han recibido velas nuevas.");
                    return;
                }

                List<Vela> entidades = dtoList.stream()
                        .map(dto -> mapToEntity(dto, symbol, interval))
                        .collect(Collectors.toList());

                velaRepo.saveAll(entidades);
                System.out.println("Guardadas " + entidades.size() + " velas en la base de datos.");
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                System.err.println("El script de Python terminó con error (código " + exitCode + ")");
            }

        } catch (Exception e) {
            throw new RuntimeException("Error crítico en FetchService: " + e.getMessage(), e);
        }
    }

    /**
     * Mapea un Objeto de Transferencia de Datos (DTO) a una Entidad JPA.
     * Realiza una conversión segura de String a BigDecimal para garantizar la precisión financiera.
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