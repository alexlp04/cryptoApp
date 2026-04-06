package com.bottrading.backtesting.infrastructure;

import java.math.BigDecimal;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.bottrading.trading.infrastructure.persistence.TradeCsvWriter;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class FileService {

    private final TradeCsvWriter tradeWriter;
    private final StatsCsvRepository statsRepository;
    private final BacktestArtifactsCleaner cleaner;

    public FileService(TradeCsvWriter tradeWriter, StatsCsvRepository statsRepository,
                      BacktestArtifactsCleaner cleaner) {
        this.tradeWriter = tradeWriter;
        this.statsRepository = statsRepository;
        this.cleaner = cleaner;
    }


    // INTERFAZ PÚBLICA - Delegación a servicios especializados

    /**
     * Método de compatibilidad delegado a {@link StatsCsvRepository#guardarEstadisticasDelBacktest}.
     */
    public void guardarEstadisticasDelBacktest(String nombreEstrategia, String timeframe, String jsonResultado) {
        statsRepository.guardarEstadisticasDelBacktest(nombreEstrategia, timeframe, jsonResultado);
    }

    /**
     * Alias de compatibilidad: delega en {@link StatsCsvRepository#guardarEstadisticasDelBacktest}.
     */
    public void guardarResultadosCompletos(String nombreEstrategia, String timeframe, String jsonResultado) {
        log.warn("guardarResultadosCompletos() está deprecado. Use guardarEstadisticasDelBacktest()");
        guardarEstadisticasDelBacktest(nombreEstrategia, timeframe, jsonResultado);
    }

    /**
     * Método de compatibilidad delegado a {@link TradeCsvWriter#guardarTrade(String, String, String, Map, boolean)}.
     */
    public synchronized void guardarTrade(String nombreEstrategia, String timeframe, String symbol,
            Map<String, Object> trade, boolean isBacktest) {
        tradeWriter.guardarTrade(nombreEstrategia, timeframe, symbol, trade, isBacktest);
    }

    /**
     * Método de compatibilidad delegado a {@link TradeCsvWriter#guardarTrade(String, String, String, String, BigDecimal, long, BigDecimal)}.
     */
    public synchronized void guardarTrade(String nombreEstrategia, String timeframe, String symbol,
            String side, BigDecimal price, long timestamp, BigDecimal pnl) {
        tradeWriter.guardarTrade(nombreEstrategia, timeframe, symbol, side, price, timestamp, pnl);
    }

    /**
     * Método de compatibilidad delegado a {@link StatsCsvRepository#guardarStats}.
     */
    public synchronized void guardarStats(String nombreEstrategia, String timeframe, Map<String, Object> stats,
            boolean isBacktest) {
        statsRepository.guardarStats(nombreEstrategia, timeframe, stats, isBacktest);
    }

    /**
     * Método de compatibilidad delegado a {@link BacktestArtifactsCleaner#verificarYLimpiarCarpetaEstrategia}.
     */
    public void verificarYLimpiarCarpetaEstrategia(String nombreEstrategia, boolean limpiarBacktestsPrevios) {
        cleaner.verificarYLimpiarCarpetaEstrategia(nombreEstrategia, limpiarBacktestsPrevios);
    }

    /**
     * Método de compatibilidad delegado a {@link StatsCsvRepository#leerStatsActuales}.
     */
    public Map<String, Object> leerStatsActuales(String nombreEstrategia, String timeframe, String symbol) {
        return statsRepository.leerStatsActuales(nombreEstrategia, timeframe, symbol);
    }
}