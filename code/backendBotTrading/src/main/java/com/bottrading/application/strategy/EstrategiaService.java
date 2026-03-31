package com.bottrading.application.strategy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

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
     * @deprecated Delegado a {@link StrategyLifecycleApplicationService#iniciarTradeRT}
     */
    @Deprecated(forRemoval = true)
    public void iniciarTradeRT(String nombreEstra, String nombreModelo, String tf, List<String> coins,
            boolean isReal, Long walletId, BigDecimal risk, BigDecimal capital) {
        lifecycleService.iniciarTradeRT(nombreEstra, nombreModelo, tf, coins, isReal, walletId, risk, capital);
    }

    /**
     * @deprecated Delegado a {@link StrategyLifecycleApplicationService#iniciarEstrategiaDetenida}
     */
    @Deprecated(forRemoval = true)
    public void iniciarEstrategiaDetenida(Long instanciaId) {
        lifecycleService.iniciarEstrategiaDetenida(instanciaId);
    }

    /**
     * @deprecated Delegado a {@link StrategyLifecycleApplicationService#iniciarTodasDetenidas}
     */
    @Deprecated(forRemoval = true)
    public void iniciarTodasDetenidas() {
        lifecycleService.iniciarTodasDetenidas();
    }

    /**
     * @deprecated Delegado a {@link StrategyLifecycleApplicationService#terminarEstrategia}
     */
    @Deprecated(forRemoval = true)
    public void terminarEstrategia(long instanciaId) {
        lifecycleService.terminarEstrategia(instanciaId);
    }

    /**
     * @deprecated Delegado a {@link StrategyLifecycleApplicationService#terminarTodas}
     */
    @Deprecated(forRemoval = true)
    public void terminarTodas() {
        lifecycleService.terminarTodas();
    }

    /**
     * @deprecated Delegado a {@link StrategyLifecycleApplicationService#detenerEstrategia}
     */
    @Deprecated(forRemoval = true)
    public void detenerEstrategia(long instanciaId) {
        lifecycleService.detenerEstrategia(instanciaId);
    }

    /**
     * @deprecated Delegado a {@link StrategyLifecycleApplicationService#detenerTodas}
     */
    @Deprecated(forRemoval = true)
    public void detenerTodas() {
        lifecycleService.detenerTodas();
    }


    /**
     * @deprecated Delegado a {@link StrategyQueryService#listarEstrategias}
     */
    @Deprecated(forRemoval = true)
    public List<String> listarEstrategias() {
        return queryService.listarEstrategias();
    }

    /**
     * @deprecated Delegado a {@link StrategyQueryService#listarEstrategiasActivas}
     */
    @Deprecated(forRemoval = true)
    public List<String> listarEstrategiasActivas() {
        return queryService.listarEstrategiasActivas();
    }

    /**
     * @deprecated Delegado a {@link StrategyQueryService#listarEstrategiasTerminadas}
     */
    @Deprecated(forRemoval = true)
    public List<String> listarEstrategiasTerminadas() {
        return queryService.listarEstrategiasTerminadas();
    }

    /**
     * @deprecated Delegado a {@link StrategyQueryService#listarEstrategiasDetenidas}
     */
    @Deprecated(forRemoval = true)
    public List<String> listarEstrategiasDetenidas() {
        return queryService.listarEstrategiasDetenidas();
    }


    /**
     * @deprecated Delegado a {@link StrategyCatalogService#listarFicherosDeEstrategias}
     */
    @Deprecated(forRemoval = true)
    public List<String> listarFicherosDeEstrategias() {
        return catalogService.listarFicherosDeEstrategias();
    }

    /**
     * @deprecated Delegado a {@link StrategyCatalogService#getCapitalComprometido}
     */
    @Deprecated(forRemoval = true)
    public BigDecimal getCapitalComprometido(Long walletAsociada) {
        return catalogService.getCapitalComprometido(walletAsociada);
    }


    /**
     * @deprecated Delegado a {@link StrategyBacktestApplicationService#ejecutarBacktest}
     */
    @Deprecated(forRemoval = true)
    public void ejecutarBacktest(String nombreEstra, String tf, List<String> coins,
            BigDecimal capitalAsignado, BigDecimal risk, boolean limpiarBacktestsPrevios,
            boolean guardarTrades) throws Exception {
        backtestService.ejecutarBacktest(nombreEstra, tf, coins, capitalAsignado, risk, 
                                         limpiarBacktestsPrevios, guardarTrades);
    }
}
