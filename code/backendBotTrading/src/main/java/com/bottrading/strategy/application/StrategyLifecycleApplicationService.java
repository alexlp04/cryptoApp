package com.bottrading.strategy.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bottrading.strategy.application.port.in.StrategyLifecycleUseCase;
import com.bottrading.strategy.application.port.out.InstanciaEstrategiaRepositoryPort;
import com.bottrading.strategy.domain.EstadoEstrategia;
import com.bottrading.strategy.domain.InstanciaEstrategia;
import com.bottrading.trading.application.port.in.AccountingUseCase;
import com.bottrading.trading.infrastructure.bridge.StrategyRuntimeCoordinator;

import lombok.extern.slf4j.Slf4j;

/**
 * Servicio de aplicación para gestión del ciclo de vida de estrategias.
 * Orquesta: creación, inicialización, pausa, terminación y liquidación.
 * 
 * Responsabilidad única: operaciones de cambio de estado de estrategias.
 */
@Slf4j
@Service
public class StrategyLifecycleApplicationService implements StrategyLifecycleUseCase {

    private static final String INSTANCIA_ID_NULL_MSG = "instanciaId no puede ser null";

        private final InstanciaEstrategiaRepositoryPort instanciaRepo;
        private final AccountingUseCase accountingService;
    private final StrategyRuntimeCoordinator runtimeCoordinator;

    public StrategyLifecycleApplicationService(
            InstanciaEstrategiaRepositoryPort instanciaRepo,
            AccountingUseCase accountingService,
            StrategyRuntimeCoordinator runtimeCoordinator) {
        this.instanciaRepo = instanciaRepo;
        this.accountingService = accountingService;
        this.runtimeCoordinator = runtimeCoordinator;
    }

    /**
     * Crea, persiste y activa financieramente una estrategia dentro de una transacción.
     * Método separado deliberadamente para que el lanzamiento del proceso Python
     * ocurra FUERA de la transacción y evitar resource leaks si el commit falla.
     */
    @Transactional
    @SuppressWarnings("java:S107")
    public InstanciaEstrategia crearYActivarEstrategia(
            String nombreEstra, String nombreModelo, String tf, List<String> coins,
            boolean isReal, Long walletId, BigDecimal risk, BigDecimal capital) {

        InstanciaEstrategia instancia = InstanciaEstrategia.inicializar(
                nombreEstra, nombreModelo, tf, coins, isReal, walletId, risk, capital);

        instancia = instanciaRepo.save(Objects.requireNonNull(instancia, "Instancia no puede ser null"));
        log.info("Estrategia {} creada con ID {}", nombreEstra, instancia.getId());

        accountingService.activateStrategy(walletId, instancia.getId(), capital);
        return instancia;
    }

    /**
     * Inicia una nueva instancia de estrategia en Tiempo Real.
     * 1. Crea el registro en BBDD y reserva el capital (dentro de TX).
     * 2. Lanza el proceso Python FUERA de la TX para evitar resource leaks.
     */
    @SuppressWarnings("java:S107")
    @Override
    public void iniciarTradeRT(String nombreEstra, String nombreModelo, String tf, List<String> coins,
            boolean isReal, Long walletId, BigDecimal risk, BigDecimal capital) {

        InstanciaEstrategia instancia = crearYActivarEstrategia(
                nombreEstra, nombreModelo, tf, coins, isReal, walletId, risk, capital);

        runtimeCoordinator.ejecutarTradeEnTiempoReal(instancia, coins);
    }

    /**
     * Reanuda una estrategia detenida (PAUSA -> ACTIVA).
     */
    @Override
    public void iniciarEstrategiaDetenida(Long instanciaId) {
        Optional<InstanciaEstrategia> opt = instanciaRepo.findById(Objects.requireNonNull(instanciaId, INSTANCIA_ID_NULL_MSG));
        if (opt.isEmpty()) {
            log.error("Estrategia no encontrada: {}", instanciaId);
            return;
        }
        
        InstanciaEstrategia instancia = opt.get();
        if (EstadoEstrategia.DETENIDA != instancia.getEstado()) {
            log.error("Solo puedo iniciar estrategias en estado DETENIDA. Estado actual: {}",
                     instancia.getEstado());
            return;
        }

        instancia.setEstado(EstadoEstrategia.ACTIVA);
        instanciaRepo.save(instancia);
        runtimeCoordinator.ejecutarTradeEnTiempoReal(instancia, instancia.getSimbolos());
        log.info("Estrategia {} reanudada", instanciaId);
    }

    /**
     * Reanuda todas las estrategias detenidas.
     */
    @Override
    public void iniciarTodasDetenidas() {
        List<InstanciaEstrategia> detenidas = instanciaRepo.findByEstado(EstadoEstrategia.DETENIDA);
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
    @Transactional
    @Override
    public void detenerEstrategia(Long instanciaId) {
        // Detener el proceso Python
        runtimeCoordinator.detenerEstrategia(instanciaId);

        // Actualizar estado a DETENIDA con lock pesimista para evitar estado inconsistente
        instanciaRepo.findByIdWithLock(Objects.requireNonNull(instanciaId, INSTANCIA_ID_NULL_MSG))
                .filter(e -> EstadoEstrategia.ACTIVA == e.getEstado())
                .ifPresent(instancia -> {
                    instancia.setEstado(EstadoEstrategia.DETENIDA);
                    instanciaRepo.save(instancia);
                    log.info("Estrategia {} PAUSADA (fondos en reserva)", instanciaId);
                });
    }

    /**
     * Pausa todas las estrategias activas.
     */
    @Override
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
    @Transactional
    @Override
    public void terminarEstrategia(Long instanciaId) {
        // Detener proceso
        runtimeCoordinator.detenerEstrategia(instanciaId);

        // Liquidación financiera con lock pesimista para garantizar atomicidad
        instanciaRepo.findByIdWithLock(Objects.requireNonNull(instanciaId, INSTANCIA_ID_NULL_MSG))
                .filter(e -> EstadoEstrategia.TERMINADA != e.getEstado())
                .ifPresent(instancia -> {
                    accountingService.closeStrategy(instancia.getWalletAsociada(), instancia.getId());
                    log.info("Estrategia {} TERMINADA (fondos retornados)", instanciaId);
                });
    }

    /**
     * Termina todas las estrategias activas.
     */
    @Override
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
