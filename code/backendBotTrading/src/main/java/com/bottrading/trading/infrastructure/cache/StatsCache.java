package com.bottrading.trading.infrastructure.cache;

import com.bottrading.backtesting.infrastructure.FileService;
import com.bottrading.shared.utils.SafeParser;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Cache en memoria para estadísticas de trades.
 * Evita lecturas/escrituras constantes de CSV.
 * 
 * Estructura:
 * estrategia -> (symbol+timeframe) -> statsMap
 * 
 * Se sincroniza a disco cada 30 segundos o cuando se cierra la estrategia.
 */
@Slf4j
public class StatsCache {
    
    private final Map<String, Map<String, Map<String, Object>>> cache = new ConcurrentHashMap<>();
    private final FileService fileService;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    
    private static final int FLUSH_INTERVAL_SECONDS = 30;

    public StatsCache(FileService fileService) {
        this.fileService = fileService;
        // Iniciar flush automático cada 30 segundos
        startAutoFlush();
    }

    /**
     * Obtiene las estadísticas actuales de una estrategia/símbolo/timeframe.
     * Si no existen, las carga del CSV o retorna mapa vacío.
     */
    public Map<String, Object> getStats(String nombreEstrategia, String timeframe, String symbol) {
        String key = symbol + "_" + timeframe;
        
        return cache
            .computeIfAbsent(nombreEstrategia, k -> new ConcurrentHashMap<>())
            .computeIfAbsent(key, k -> {
                // Intentar cargar del CSV
                Map<String, Object> statsDelArchivo = fileService.leerStatsActuales(nombreEstrategia, timeframe, symbol);
                return statsDelArchivo.isEmpty() ? new ConcurrentHashMap<>() : new ConcurrentHashMap<>(statsDelArchivo);
            });
    }

    /**
     * Actualiza las estadísticas en caché.
     * NO recurre al archivo hasta flush().
     */
    public void updateStats(String nombreEstrategia, String timeframe, String symbol, Map<String, Object> updates) {
        Map<String, Object> currentStats = getStats(nombreEstrategia, timeframe, symbol);
        currentStats.putAll(updates);
        log.debug("Stats actualizadas en caché: {} [{}] {}", nombreEstrategia, symbol, timeframe);
    }

    /**
     * Incrementa un contador (ej: operaciones ganadas).
     */
    public void incrementCounter(String nombreEstrategia, String timeframe, String symbol, String counterKey) {
        Map<String, Object> stats = getStats(nombreEstrategia, timeframe, symbol);
        int current = SafeParser.toInt(stats.get(counterKey), 0);
        stats.put(counterKey, current + 1);
    }

    /**
     * Suma un valor a un BigDecimal (ej: retorno acumulado).
     */
    public void addToBigDecimal(String nombreEstrategia, String timeframe, String symbol, String key, BigDecimal amount) {
        Map<String, Object> stats = getStats(nombreEstrategia, timeframe, symbol);
        BigDecimal current = SafeParser.toBigDecimal(stats.get(key), BigDecimal.ZERO);
        stats.put(key, current.add(amount));
    }

    /**
     * Guarda todas las estadísticas en caché a disco (CSV).
     * Se llama automáticamente cada 30 segundos y al cerrar la estrategia.
     */
    public void flushAllToDisk() {
        cache.forEach((nombreEstrategia, estrategiaStats) ->
            estrategiaStats.forEach((key, stats) -> {
                String[] parts = key.split("_", 2);
                if (parts.length == 2)
                    flushStrategySymbolStats(nombreEstrategia, parts[1], parts[0], stats);
            })
        );
    }

    /**
     * Guarda las stats de una estrategia/símbolo/timeframe específico.
     */
    public void flushStrategySymbolStats(String nombreEstrategia, String timeframe, String symbol, Map<String, Object> stats) {
        try {
            fileService.guardarStats(nombreEstrategia, timeframe, stats, false);
            log.debug("Stats guardadas en CSV: {} [{}] {}", nombreEstrategia, symbol, timeframe);
        } catch (Exception e) {
            log.error("Error guardando stats en CSV: {}", e.getMessage(), e);
        }
    }

    /**
     * Limpia caché de una estrategia antes de cerrarla.
     */
    public void clearStrategy(String nombreEstrategia) {
        Map<String, Map<String, Object>> estrategiaStats = cache.remove(nombreEstrategia);
        if (estrategiaStats != null) {
            log.info("Caché de {} limpiada ({} registros)", nombreEstrategia, estrategiaStats.size());
        }
    }

    /**
     * Inicia flush automático en background.
     */
    private void startAutoFlush() {
        scheduler.scheduleAtFixedRate(
            this::flushAllToDisk,
            FLUSH_INTERVAL_SECONDS,
            FLUSH_INTERVAL_SECONDS,
            TimeUnit.SECONDS
        );
    }

    /**
     * Detiene el scheduler.
     */
    public void shutdown() {
        flushAllToDisk(); // Flush final
        scheduler.shutdownNow();
        log.info("StatsCache apagado");
    }
}
