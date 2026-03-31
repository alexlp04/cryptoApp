package com.bottrading.application.strategy;

import com.bottrading.domain.strategy.InstanciaEstrategia;
import com.bottrading.domain.strategy.InstanciaEstrategiaRepository;
import com.bottrading.application.trading.AccountingService;
import com.bottrading.infrastructure.bridge.StrategyRuntimeCoordinator;
import com.bottrading.utils.AppConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Servicio de aplicación para gestión del ciclo de vida de estrategias.
 * Orquesta: creación, inicialización, pausa, terminación y liquidación.
 * 
 * Responsabilidad única: operaciones de cambio de estado de estrategias.
 */
@Slf4j
@Service
public class StrategyLifecycleApplicationService {

    private final InstanciaEstrategiaRepository instanciaRepo;
    private final AccountingService accountingService;
    private final StrategyRuntimeCoordinator runtimeCoordinator;

    public StrategyLifecycleApplicationService(
            InstanciaEstrategiaRepository instanciaRepo,
            AccountingService accountingService,
            StrategyRuntimeCoordinator runtimeCoordinator) {
        this.instanciaRepo = instanciaRepo;
        this.accountingService = accountingService;
        this.runtimeCoordinator = runtimeCoordinator;
    }

    /**
     * Inicia una nueva instancia de estrategia en Tiempo Real.
     * 1. Crea el registro en BBDD.
     * 2. Reserva el capital.
     * 3. Lanza el proceso de Python.
     */
    @Transactional
    public void iniciarTradeRT(String nombreEstra, String nombreModelo, String tf, List<String> coins,
            boolean isReal, Long walletId, BigDecimal risk, BigDecimal capital) {
        
        InstanciaEstrategia instancia = InstanciaEstrategia.inicializar(
                nombreEstra, nombreModelo, tf, coins, isReal, walletId, risk, capital);

        instancia = instanciaRepo.save(instancia);
        log.info("Estrategia {} creada con ID {}", nombreEstra, instancia.getId());

        accountingService.activateStrategy(walletId, instancia.getId(), capital);

        runtimeCoordinator.ejecutarTradeEnTiempoReal(instancia, coins);
    }

    /**
     * Reanuda una estrategia detenida (PAUSA -> ACTIVA).
     */
    public void iniciarEstrategiaDetenida(Long instanciaId) {
        Optional<InstanciaEstrategia> opt = instanciaRepo.findById(instanciaId);
        if (opt.isEmpty()) {
            log.error("Estrategia no encontrada: {}", instanciaId);
            return;
        }
        
        InstanciaEstrategia instancia = opt.get();
        if (!AppConstants.KEY_DETENIDA.equals(instancia.getEstado())) {
            log.error("Solo puedo iniciar estrategias en estado DETENIDA. Estado actual: {}", 
                     instancia.getEstado());
            return;
        }

        instancia.setEstado(AppConstants.KEY_ACTIVA);
        instanciaRepo.save(instancia);
        runtimeCoordinator.ejecutarTradeEnTiempoReal(instancia, instancia.getSimbolos());
        log.info("Estrategia {} reanudada", instanciaId);
    }

    /**
     * Reanuda todas las estrategias detenidas.
     */
    public void iniciarTodasDetenidas() {
        List<InstanciaEstrategia> detenidas = instanciaRepo.findByEstado(AppConstants.KEY_DETENIDA);
        if (detenidas.isEmpty()) {
            log.info("No hay estrategias detenidas.");
            return;
        }
        log.info("Reanudando {} estrategias...", detenidas.size());
        detenidas.forEach(inst -> iniciarEstrategiaDetenida(inst.getId()));
    }

    /**
     * Pausa una estrategia (ACTIVA -> DETENIDA).
     * Los fondos se mantienen en reserva.
     */
    public void detenerEstrategia(Long instanciaId) {
        // Detener el proceso Python
        runtimeCoordinator.detenerEstrategia(instanciaId);

        // Actualizar estado a DETENIDA
        Optional<InstanciaEstrategia> opt = instanciaRepo.findById(instanciaId);
        if (opt.isPresent() && AppConstants.KEY_ACTIVA.equals(opt.get().getEstado())) {
            InstanciaEstrategia instancia = opt.get();
            instancia.setEstado(AppConstants.KEY_DETENIDA);
            instanciaRepo.save(instancia);
            log.info("Estrategia {} PAUSADA (fondos en reserva)", instanciaId);
        }
    }

    /**
     * Pausa todas las estrategias activas.
     */
    public void detenerTodas() {
        Set<Long> ids = runtimeCoordinator.getIdsEstrategiasActivas();
        if (ids.isEmpty()) {
            log.info("No hay estrategias activas.");
            return;
        }
        log.info("Pausando {} estrategias...", ids.size());
        ids.forEach(this::detenerEstrategia);
    }

    /**
     * Termina y liquida una estrategia (cualquier estado -> TERMINADA).
     * Devuelve los fondos a la billetera.
     */
    public void terminarEstrategia(Long instanciaId) {
        // Detener proceso
        runtimeCoordinator.detenerEstrategia(instanciaId);

        // Liquidación financiera
        Optional<InstanciaEstrategia> opt = instanciaRepo.findById(instanciaId);
        if (opt.isPresent()) {
            InstanciaEstrategia instancia = opt.get();
            if (!AppConstants.KEY_TERMINADA.equals(instancia.getEstado())) {
                accountingService.closeStrategy(instancia.getWalletAsociada(), instancia.getId());
                log.info("Estrategia {} TERMINADA (fondos retornados)", instanciaId);
            }
        }
    }

    /**
     * Termina todas las estrategias activas.
     */
    public void terminarTodas() {
        Set<Long> ids = runtimeCoordinator.getIdsEstrategiasActivas();
        if (ids.isEmpty()) {
            log.info("No hay estrategias activas.");
            return;
        }
        log.info("Terminando {} estrategias...", ids.size());
        ids.forEach(this::terminarEstrategia);
    }
}
