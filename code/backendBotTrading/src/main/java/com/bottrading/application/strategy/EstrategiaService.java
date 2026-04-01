package com.bottrading.application.strategy;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * ⚠️ DEPRECATED - Facade de compatibilidad hacia servicios especializados.
 * Esta clase mantiene la interfaz pública del EstrategiaService original
 * pero delega toda la implementación a servicios especializados por responsabilidad.
 *
 * Mapeo de métodos:
 * - Lifecycle: {@link StrategyLifecycleApplicationService}
 * - Queries: {@link StrategyQueryService}
 * - Backtesting: {@link StrategyBacktestApplicationService}
 * - Catálogo: {@link StrategyCatalogService}
 *
 * NOTA: Esta clase será eliminada en refactores posteriores.
 * Histórico: Fase 2 (2026-03-31) - Extraído a servicios especializados.
 */
@Slf4j
@Service
public class EstrategiaService {

    private final StrategyLifecycleApplicationService lifecycleService;
    private final StrategyQueryService queryService;
    private final StrategyBacktestApplicationService backtestService;
    private final StrategyCatalogService catalogService;

    public EstrategiaService(
            StrategyLifecycleApplicationService lifecycleService,
            StrategyQueryService queryService,
            StrategyBacktestApplicationService backtestService,
            StrategyCatalogService catalogService) {
        this.lifecycleService = lifecycleService;
        this.queryService = queryService;
        this.backtestService = backtestService;
        this.catalogService = catalogService;
    }

    // INTERFAZ PÚBLICA - Delegación a servicios especializados


    /**
     * Método de compatibilidad delegado a {@link StrategyLifecycleApplicationService#iniciarTradeRT}.
     */
    @SuppressWarnings("java:S107")
    public void iniciarTradeRT(String nombreEstra, String nombreModelo, String tf, List<String> coins,
            boolean isReal, Long walletId, BigDecimal risk, BigDecimal capital) {
        lifecycleService.iniciarTradeRT(nombreEstra, nombreModelo, tf, coins, isReal, walletId, risk, capital);
    }

    /**
     * Método de compatibilidad delegado a {@link StrategyLifecycleApplicationService#iniciarEstrategiaDetenida}.
     */
    public void iniciarEstrategiaDetenida(Long instanciaId) {
        lifecycleService.iniciarEstrategiaDetenida(instanciaId);
    }

    /**
     * Método de compatibilidad delegado a {@link StrategyLifecycleApplicationService#iniciarTodasDetenidas}.
     */
    public void iniciarTodasDetenidas() {
        lifecycleService.iniciarTodasDetenidas();
    }

    /**
     * Método de compatibilidad delegado a {@link StrategyLifecycleApplicationService#terminarEstrategia}.
     */
    public void terminarEstrategia(long instanciaId) {
        lifecycleService.terminarEstrategia(instanciaId);
    }

    /**
     * Método de compatibilidad delegado a {@link StrategyLifecycleApplicationService#terminarTodas}.
     */
    public void terminarTodas() {
        lifecycleService.terminarTodas();
    }

    /**
     * Método de compatibilidad delegado a {@link StrategyLifecycleApplicationService#detenerEstrategia}.
     */
    public void detenerEstrategia(long instanciaId) {
        lifecycleService.detenerEstrategia(instanciaId);
    }

    /**
     * Método de compatibilidad delegado a {@link StrategyLifecycleApplicationService#detenerTodas}.
     */
    public void detenerTodas() {
        lifecycleService.detenerTodas();
    }


    /**
     * Método de compatibilidad delegado a {@link StrategyQueryService#listarEstrategias}.
     */
    public List<String> listarEstrategias() {
        return queryService.listarEstrategias();
    }

    /**
     * Método de compatibilidad delegado a {@link StrategyQueryService#listarEstrategiasActivas}.
     */
    public List<String> listarEstrategiasActivas() {
        return queryService.listarEstrategiasActivas();
    }

    /**
     * Método de compatibilidad delegado a {@link StrategyQueryService#listarEstrategiasTerminadas}.
     */
    public List<String> listarEstrategiasTerminadas() {
        return queryService.listarEstrategiasTerminadas();
    }

    /**
     * Método de compatibilidad delegado a {@link StrategyQueryService#listarEstrategiasDetenidas}.
     */
    public List<String> listarEstrategiasDetenidas() {
        return queryService.listarEstrategiasDetenidas();
    }


    /**
     * Método de compatibilidad delegado a {@link StrategyCatalogService#listarFicherosDeEstrategias}.
     */
    public List<String> listarFicherosDeEstrategias() {
        return catalogService.listarFicherosDeEstrategias();
    }

    /**
     * Método de compatibilidad delegado a {@link StrategyCatalogService#getCapitalComprometido}.
     */
    public BigDecimal getCapitalComprometido(Long walletAsociada) {
        return catalogService.getCapitalComprometido(walletAsociada);
    }


    /**
     * Método de compatibilidad delegado a {@link StrategyBacktestApplicationService#ejecutarBacktest}.
     */
    public void ejecutarBacktest(String nombreEstra, String tf, List<String> coins,
            BigDecimal capitalAsignado, BigDecimal risk, boolean limpiarBacktestsPrevios,
            boolean guardarTrades) throws Exception {
        backtestService.ejecutarBacktest(nombreEstra, tf, coins, capitalAsignado, risk, 
                                         limpiarBacktestsPrevios, guardarTrades);
    }
}
