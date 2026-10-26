package com.bottrading.strategy.application;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.bottrading.strategy.application.port.in.StrategyLifecycleUseCase;
import com.bottrading.strategy.application.port.out.InstanciaEstrategiaRepositoryPort;
import com.bottrading.strategy.domain.EstadoEstrategia;
import com.bottrading.strategy.domain.InstanciaEstrategia;
import com.bottrading.shared.exceptions.ValidationException;
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
    /**
     * Las transacciones se abren con el template y no con @Transactional: este servicio se llama
     * a sí mismo (this::detenerEstrategia...) y la autoinvocación se salta el proxy, con lo que
     * @Transactional y el lock pesimista no tenían efecto.
     */
    private final TransactionTemplate tx;

    public StrategyLifecycleApplicationService(
            InstanciaEstrategiaRepositoryPort instanciaRepo,
            AccountingUseCase accountingService,
            StrategyRuntimeCoordinator runtimeCoordinator,
            TransactionTemplate tx) {
        this.instanciaRepo = instanciaRepo;
        this.accountingService = accountingService;
        this.runtimeCoordinator = runtimeCoordinator;
        this.tx = tx;
    }

    /**
     * Crea, persiste y activa financieramente una estrategia. Debe ejecutarse dentro de una
     * transacción (iniciarTradeRT la abre): si la activación falla no queda ninguna fila CREADA.
     */
    @SuppressWarnings("java:S107")
    public InstanciaEstrategia crearYActivarEstrategia(
            String nombreEstra, String nombreModelo, String tf, List<String> coins,
            boolean isReal, Long walletId, BigDecimal risk, BigDecimal capital, Long usuarioId) {
        return crearYActivarEstrategia(nombreEstra, nombreModelo, tf, coins, isReal, walletId, risk, capital,
                InstanciaEstrategia.MODO_SENAL_CIERRE, usuarioId);
    }

    @SuppressWarnings("java:S107")
    public InstanciaEstrategia crearYActivarEstrategia(
            String nombreEstra, String nombreModelo, String tf, List<String> coins,
            boolean isReal, Long walletId, BigDecimal risk, BigDecimal capital, String signalMode,
            Long usuarioId) {

        InstanciaEstrategia instancia = InstanciaEstrategia.inicializar(
                nombreEstra, nombreModelo, tf, coins, isReal, walletId, risk, capital);
        instancia.setUsuarioId(usuarioId);
        instancia.setSignalMode(signalMode); // valida el valor antes de persistir nada
        instancia.setReanudarAlArrancar(true);

        instancia = instanciaRepo.save(Objects.requireNonNull(instancia, "Instancia no puede ser null"));
        log.info("Estrategia {} creada con ID {}", nombreEstra, instancia.getId());

        accountingService.activateStrategy(walletId, instancia.getId(), capital);
        return instancia;
    }

    /**
     * Inicia una nueva instancia de estrategia en Tiempo Real.
     * 1. Crea el registro en BBDD y reserva el capital (en una transacción).
     * 2. Lanza el proceso Python FUERA de la transacción para evitar resource leaks.
     */
    @SuppressWarnings("java:S107")
    @Override
    public void iniciarTradeRT(String nombreEstra, String nombreModelo, String tf, List<String> coins,
            boolean isReal, Long walletId, BigDecimal risk, BigDecimal capital, String signalMode,
            Long usuarioId) {

        if (isReal) {
            // Dominio: sin adaptador de exchange una estrategia "real" se ejecutaría como simulación.
            throw new ValidationException("El modo real no está disponible: no hay ningún exchange conectado.");
        }

        InstanciaEstrategia instancia = tx.execute(status -> crearYActivarEstrategia(
                nombreEstra, nombreModelo, tf, coins, isReal, walletId, risk, capital, signalMode, usuarioId));

        lanzarRuntime(Objects.requireNonNull(instancia), coins);
    }

    /**
     * Lanza el runtime y, si no arranca, devuelve la estrategia a DETENIDA: dejarla ACTIVA sin
     * proceso la hacía inalcanzable.
     */
    private void lanzarRuntime(InstanciaEstrategia instancia, List<String> symbols) {
        try {
            runtimeCoordinator.ejecutarTradeEnTiempoReal(instancia, symbols);
        } catch (RuntimeException e) {
            log.error("No se pudo lanzar el runtime de la estrategia {}: se deja DETENIDA", instancia.getId(), e);
            cambiarEstado(instancia.getId(), EstadoEstrategia.ACTIVA, EstadoEstrategia.DETENIDA);
            throw e;
        }
    }

    /** Cambia el estado bajo lock pesimista solo si sigue en el estado esperado. */
    private boolean cambiarEstado(Long instanciaId, EstadoEstrategia desde, EstadoEstrategia hasta) {
        return Boolean.TRUE.equals(tx.execute(status ->
                instanciaRepo.findByIdWithLock(instanciaId)
                        .filter(e -> desde == e.getEstado())
                        .map(e -> {
                            e.setEstado(hasta);
                            instanciaRepo.save(e);
                            return true;
                        })
                        .orElse(false)));
    }

    /**
     * Reanuda una estrategia detenida (PAUSA -> ACTIVA). También reanuda una ACTIVA huérfana
     * (sin runtime vivo), p. ej. tras salir el motor Python con código 0.
     */
    @Override
    public void iniciarEstrategiaDetenida(Long instanciaId) {
        Objects.requireNonNull(instanciaId, INSTANCIA_ID_NULL_MSG);
        // Lock + comprobación de estado + cambio en una sola transacción: dos reanudaciones
        // simultáneas ya no pueden lanzar dos procesos para la misma instancia.
        InstanciaEstrategia instancia = tx.execute(status -> {
            Optional<InstanciaEstrategia> opt = instanciaRepo.findByIdWithLock(instanciaId);
            if (opt.isEmpty()) {
                log.error("Estrategia no encontrada: {}", instanciaId);
                return null;
            }
            InstanciaEstrategia e = opt.get();
            boolean activaHuerfana = EstadoEstrategia.ACTIVA == e.getEstado()
                    && !runtimeCoordinator.getIdsEstrategiasActivas().contains(instanciaId);
            if (EstadoEstrategia.DETENIDA != e.getEstado() && !activaHuerfana) {
                log.error("Solo puedo iniciar estrategias en estado DETENIDA (o ACTIVA sin proceso). Estado actual: {}",
                        e.getEstado());
                return null;
            }
            e.setEstado(EstadoEstrategia.ACTIVA);
            e.setReanudarAlArrancar(true);
            return instanciaRepo.save(e);
        });
        if (instancia == null) {
            return;
        }
        lanzarRuntime(instancia, instancia.getSimbolos());
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
     * Los fondos se mantienen en reserva. El proceso se destruye DESPUÉS de confirmar el estado:
     * si el commit fallaba, el proceso ya estaba muerto y la estrategia seguía ACTIVA.
     */
    @Override
    public void detenerEstrategia(Long instanciaId) {
        pausar(instanciaId, false);
    }

    /**
     * Pausa y, si se pausa a mano, quita la marca de reanudación (también a una ya DETENIDA): 'stop' es una orden
     * de no volver a arrancar sola hasta el próximo 'start'. El apagado de la aplicación la conserva.
     */
    private void pausar(Long instanciaId, boolean conservarReanudacion) {
        Objects.requireNonNull(instanciaId, INSTANCIA_ID_NULL_MSG);
        tx.executeWithoutResult(status -> instanciaRepo.findByIdWithLock(instanciaId).ifPresent(e -> {
            boolean pausada = EstadoEstrategia.ACTIVA == e.getEstado();
            boolean desmarcada = !conservarReanudacion && e.isReanudarAlArrancar();
            if (pausada) {
                e.setEstado(EstadoEstrategia.DETENIDA);
            }
            if (desmarcada) {
                e.setReanudarAlArrancar(false);
            }
            if (pausada || desmarcada) {
                instanciaRepo.save(e);
            }
            if (pausada) {
                log.info("Estrategia {} PAUSADA (fondos en reserva)", instanciaId);
            }
        }));
        runtimeCoordinator.detenerEstrategia(instanciaId);
    }

    @Override
    public void pausarPorApagado() {
        Set<Long> ids = idsActivos();
        if (ids.isEmpty()) {
            return;
        }
        log.info("Apagando: pausando {} estrategias; se reanudarán solas al arrancar si está habilitado", ids.size());
        ids.forEach(id -> pausar(id, true));
    }

    @Override
    public void reanudarMarcadas() {
        for (InstanciaEstrategia i : instanciaRepo.findByEstadoAndReanudarAlArrancarTrue(EstadoEstrategia.DETENIDA)) {
            try {
                iniciarEstrategiaDetenida(i.getId());
            } catch (RuntimeException e) { // una estrategia que no arranca no impide reanudar las demás
                log.error("No se pudo reanudar la estrategia {} al arrancar", i.getId(), e);
            }
        }
    }

    /**
     * Pausa todas las estrategias activas. Parte de la BBDD (fuente de verdad) y del runtime:
     * las ACTIVA huérfanas tras una caída solo estaban en la BBDD y no se podían parar en bloque.
     */
    @Override
    public void detenerTodas() {
        Set<Long> ids = idsActivos();
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
    @Override
    public void terminarEstrategia(Long instanciaId) {
        Objects.requireNonNull(instanciaId, INSTANCIA_ID_NULL_MSG);
        tx.executeWithoutResult(status ->
                instanciaRepo.findByIdWithLock(instanciaId)
                        .filter(e -> EstadoEstrategia.TERMINADA != e.getEstado())
                        .ifPresent(instancia -> {
                            accountingService.closeStrategy(instancia.getWalletAsociada(), instancia.getId());
                            log.info("Estrategia {} TERMINADA (fondos retornados)", instanciaId);
                        }));
        runtimeCoordinator.detenerEstrategia(instanciaId);
    }

    /**
     * Termina todas las estrategias activas (orden explícita del usuario; el apagado solo pausa).
     */
    @Override
    public void terminarTodas() {
        Set<Long> ids = idsActivos();
        if (ids.isEmpty()) {
            log.info("No hay estrategias activas.");
            return;
        }
        log.info("Terminando {} estrategias...", ids.size());
        ids.forEach(this::terminarEstrategia);
    }

    /**
     * Al arrancar no hay ningún runtime: las instancias que la BBDD da por ACTIVA son huérfanas de
     * una caída o parada brusca. Se pasan a DETENIDA (fondos en reserva) para que se puedan
     * reanudar con 'start' y no figuren como capital en marcha.
     */
    @Override
    public void reconciliarEstadoConRuntime() {
        Set<Long> vivas = runtimeCoordinator.getIdsEstrategiasActivas();
        for (InstanciaEstrategia i : instanciaRepo.findByEstado(EstadoEstrategia.ACTIVA)) {
            if (!vivas.contains(i.getId())
                    && cambiarEstado(i.getId(), EstadoEstrategia.ACTIVA, EstadoEstrategia.DETENIDA)) {
                log.warn("Estrategia {} estaba ACTIVA sin proceso: pasa a DETENIDA", i.getId());
            }
        }
    }

    @Override
    public Set<Long> idsConProceso() {
        return runtimeCoordinator.getIdsEstrategiasActivas();
    }

    @Override
    public void iniciarEstrategiaDetenida(Long instanciaId, Long usuarioId) {
        exigirDueno(instanciaId, usuarioId);
        iniciarEstrategiaDetenida(instanciaId);
    }

    @Override
    public void iniciarTodasDetenidas(Long usuarioId) {
        List<InstanciaEstrategia> detenidas =
                instanciaRepo.findByUsuarioIdAndEstado(usuarioId, EstadoEstrategia.DETENIDA);
        if (detenidas.isEmpty()) {
            log.info("No hay estrategias detenidas.");
            return;
        }
        detenidas.forEach(inst -> iniciarEstrategiaDetenida(inst.getId()));
    }

    @Override
    public void detenerEstrategia(Long instanciaId, Long usuarioId) {
        exigirDueno(instanciaId, usuarioId);
        detenerEstrategia(instanciaId);
    }

    @Override
    public void detenerTodas(Long usuarioId) {
        idsActivosDe(usuarioId).forEach(this::detenerEstrategia);
    }

    @Override
    public void terminarEstrategia(Long instanciaId, Long usuarioId) {
        exigirDueno(instanciaId, usuarioId);
        terminarEstrategia(instanciaId);
    }

    @Override
    public void terminarTodas(Long usuarioId) {
        idsActivosDe(usuarioId).forEach(this::terminarEstrategia);
    }

    private void exigirDueno(Long instanciaId, Long usuarioId) {
        Objects.requireNonNull(instanciaId, INSTANCIA_ID_NULL_MSG);
        boolean esSuya = instanciaRepo.findById(instanciaId)
                .map(e -> Objects.equals(e.getUsuarioId(), usuarioId))
                .orElse(false);
        if (!esSuya) {
            // El mismo mensaje para "no existe" y "es de otro": no revela qué ids existen.
            throw new ValidationException("Estrategia no encontrada: " + instanciaId);
        }
    }

    /** Ids de las activas del usuario: las ACTIVA en BBDD (fuente de verdad) y las que tienen runtime. */
    private Set<Long> idsActivosDe(Long usuarioId) {
        Set<Long> propias = new LinkedHashSet<>();
        instanciaRepo.findByUsuarioIdAndEstado(usuarioId, EstadoEstrategia.ACTIVA)
                .forEach(i -> propias.add(i.getId()));
        return propias;
    }

    private Set<Long> idsActivos() {
        Set<Long> ids = new LinkedHashSet<>(runtimeCoordinator.getIdsEstrategiasActivas());
        instanciaRepo.findByEstado(EstadoEstrategia.ACTIVA).forEach(i -> ids.add(i.getId()));
        return ids;
    }
}
