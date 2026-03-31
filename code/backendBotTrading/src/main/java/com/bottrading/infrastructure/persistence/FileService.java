package com.bottrading.infrastructure.persistence;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;

/**
 * ⚠️ DEPRECATED - Facade de compatibilidad hacia servicios especializados.
 * Esta clase mantiene la interfaz pública del FileService original
 * pero delega toda la implementación a servicios especializados por responsabilidad.
 *
 * Mapeo de métodos:
 * - Trades: {@link TradeCsvWriter}
 * - Estadísticas: {@link StatsCsvRepository}
 * - Limpieza: {@link BacktestArtifactsCleaner}
 *
 * NOTA: Esta clase será eliminada en refactores posteriores.
 * Histórico: Fase 2 (2026-03-31) - Extraído a servicios especializados.
 */
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


    // ========================================================================
    // INTERFAZ PÚBLICA - Delegación a servicios especializados
    // ========================================================================

    /**
     * @deprecated Delegado a {@link StatsCsvRepository#guardarEstadisticasDelBacktest}
     */
    @Deprecated(forRemoval = true)
    public void guardarEstadisticasDelBacktest(String nombreEstrategia, String timeframe, String jsonResultado) {
        statsRepository.guardarEstadisticasDelBacktest(nombreEstrategia, timeframe, jsonResultado);
    }

    /**
     * @deprecated Método obsoleto. Usar {@link StatsCsvRepository#guardarEstadisticasDelBacktest} en su lugar.
     */
    @Deprecated
    public void guardarResultadosCompletos(String nombreEstrategia, String timeframe, String jsonResultado) {
        log.warn("guardarResultadosCompletos() está deprecado. Use guardarEstadisticasDelBacktest()");
        guardarEstadisticasDelBacktest(nombreEstrategia, timeframe, jsonResultado);
    }

    /**
     * @deprecated Delegado a {@link TradeCsvWriter#guardarTrade(String, String, String, Map, boolean)}
     */
    @Deprecated(forRemoval = true)
    public synchronized void guardarTrade(String nombreEstrategia, String timeframe, String symbol,
            Map<String, Object> trade, boolean isBacktest) {
        tradeWriter.guardarTrade(nombreEstrategia, timeframe, symbol, trade, isBacktest);
    }

    /**
     * @deprecated Delegado a {@link TradeCsvWriter#guardarTrade(String, String, String, String, BigDecimal, long, BigDecimal)}
     */
    @Deprecated(forRemoval = true)
    public synchronized void guardarTrade(String nombreEstrategia, String timeframe, String symbol,
            String side, BigDecimal price, long timestamp, BigDecimal pnl) {
        tradeWriter.guardarTrade(nombreEstrategia, timeframe, symbol, side, price, timestamp, pnl);
    }

    /**
     * @deprecated Delegado a {@link StatsCsvRepository#guardarStats}
     */
    @Deprecated(forRemoval = true)
    public synchronized void guardarStats(String nombreEstrategia, String timeframe, Map<String, Object> stats,
            boolean isBacktest) {
        statsRepository.guardarStats(nombreEstrategia, timeframe, stats, isBacktest);
    }

    /**
     * @deprecated Delegado a {@link BacktestArtifactsCleaner#verificarYLimpiarCarpetaEstrategia}
     */
    @Deprecated(forRemoval = true)
    public void verificarYLimpiarCarpetaEstrategia(String nombreEstrategia, boolean limpiarBacktestsPrevios) {
        cleaner.verificarYLimpiarCarpetaEstrategia(nombreEstrategia, limpiarBacktestsPrevios);
    }

    /**
     * @deprecated Delegado a {@link StatsCsvRepository#leerStatsActuales}
     */
    @Deprecated(forRemoval = true)
    public Map<String, Object> leerStatsActuales(String nombreEstrategia, String timeframe, String symbol) {
        return statsRepository.leerStatsActuales(nombreEstrategia, timeframe, symbol);
    }
}