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

@Service
public class FetchService {

    @Autowired
    private VelaRepository velaRepo;

    private final Gson gson = new Gson();

    /**
     * Coordina la descarga incremental de velas para evitar duplicados.
     */
    public void fetch(String symbol, String interval) {
        System.out.println("🔍 Comprobando datos para: " + symbol + " [" + interval + "]");

        // 1. Buscamos el último timestamp registrado en la DB
        Long lastTimestamp = velaRepo.findMaxOpenTimeBySymbolAndInterval(symbol, interval);

        if (lastTimestamp == null) {
            System.out.println("No hay datos previos. Iniciando descarga completa...");
            callPythonAndSave(symbol, interval, null);
        } else {
            long now = System.currentTimeMillis();
            // Comprobamos si ha pasado tiempo suficiente para necesitar una actualización
            if (now - lastTimestamp > getIntervalMillis(interval)) {
                System.out.println("⏳ Datos desactualizados. Descargando desde: " + lastTimestamp);
                callPythonAndSave(symbol, interval, lastTimestamp);
            } else {
                System.out.println("Los datos ya están al día.");
            }
        }
    }

    /**
     * Ejecuta el script de Python y persiste el resultado en lote (Batch).
     */
    @Transactional
    private void callPythonAndSave(String symbol, String interval, Long fromTimestamp) {
        try {
            // Configurar comando: python3 fetcher.py SYMBOL INTERVAL [FROM_TIMESTAMP]
            ProcessBuilder pb = new ProcessBuilder("python3", PathConfig.FETCHER_PATH, symbol, interval);
            if (fromTimestamp != null) {
                pb.command().add(String.valueOf(fromTimestamp));
            }

            Process process = pb.start();

            // 2. Leer la salida JSON directamente del flujo (más eficiente en memoria)
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                List<VelaDTO> dtoList = gson.fromJson(reader, new TypeToken<List<VelaDTO>>() {
                }.getType());

                if (dtoList == null || dtoList.isEmpty()) {
                    System.out.println("No se han recibido velas nuevas.");
                    return;
                }

                // 3. Transformar DTOs a Entidades y guardar en lote
                List<Vela> entidades = dtoList.stream()
                        .map(dto -> mapToEntity(dto, symbol, interval))
                        .collect(Collectors.toList());

                velaRepo.saveAll(entidades);
                System.out.println("Guardadas " + entidades.size() + " velas en la base de datos.");
            }

            // Captura de errores del script de Python
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                System.err.println("El script de Python terminó con error (código " + exitCode + ")");
            }

        } catch (Exception e) {
            throw new RuntimeException("Error crítico en FetchService: " + e.getMessage(), e);
        }
    }

    /**
     * Mapeo detallado de DTO a Entidad JPA con conversión de tipos.
     */
    private Vela mapToEntity(VelaDTO dto, String symbol, String interval) {
        Vela v = new Vela();
        v.setSymbol(symbol);
        v.setInterval(interval);
        v.setOpenTime(dto.open_time);
        v.setCloseTime(dto.close_time);

        // Conversión segura de String a BigDecimal para precisión financiera
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