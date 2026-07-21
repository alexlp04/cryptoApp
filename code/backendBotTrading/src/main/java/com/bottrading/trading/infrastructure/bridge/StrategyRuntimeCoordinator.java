package com.bottrading.trading.infrastructure.bridge;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;

import com.bottrading.shared.exceptions.PythonProcessException;
import com.bottrading.shared.exceptions.SignalProcessingException;
import com.bottrading.strategy.domain.InstanciaEstrategia;
import com.bottrading.trading.application.port.in.AccountingUseCase;
import com.bottrading.trading.application.port.in.ProcessSignalUseCase;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * Orquestador principal de ejecución de estrategias en tiempo real.
 * Inyecta los supervisores especializados y coordina el flujo end-to-end.
 * 
 * Responsabilidad única: coordinación de lifecycle y streaming de señales.
 */
@Slf4j
@Service
public class StrategyRuntimeCoordinator {

    private final RealtimeProcessSupervisor processSupervisor;
    private final SignalProtocolParser signalParser;
    private final SignalRetryQueueService retryQueueService;
    private final ProcessSignalUseCase paperTradingService;
    private final AccountingUseCase accountingService;
    private final PythonBridgeFacade pythonBridgeFacade;

    /** Sin salida inicial en este plazo => el proceso no llegó a arrancar. */
    private static final long STARTUP_TIMEOUT_MS = 90_000L;
    /** Silencio máximo tolerado tras arrancar; debe superar el intervalo de heartbeat (15s). */
    private static final long INACTIVITY_TIMEOUT_MS = 60_000L;
    /** Periodo de sondeo del watchdog de inactividad. */
    private static final long WATCHDOG_POLL_MS = 1_000L;

    /** Retraso base del backoff de reinicio de estrategia. */
    private static final long RESTART_BASE_DELAY_MS = 5_000L;
    /** Tope del retraso de reinicio. */
    private static final long RESTART_MAX_DELAY_MS = 300_000L;
    /** Máximo de reinicios consecutivos sin estabilizar antes de abortar. */
    private static final int MAX_CONSECUTIVE_RESTARTS = 5;
    /** Uptime a partir del cual una ejecución se considera estable y resetea el crash-loop. */
    private static final long STABLE_UPTIME_MS = 120_000L;

    private final Map<Long, Future<?>> runtimeThreads = new ConcurrentHashMap<>();

    /** Estrategias cuya parada fue solicitada explícitamente (no deben reiniciarse). */
    private final Set<Long> stopRequested = ConcurrentHashMap.newKeySet();

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public StrategyRuntimeCoordinator(
            RealtimeProcessSupervisor processSupervisor,
            SignalProtocolParser signalParser,
            SignalRetryQueueService retryQueueService,
            ProcessSignalUseCase paperTradingService,
            AccountingUseCase accountingService,
            PythonBridgeFacade pythonBridgeFacade) {
        this.processSupervisor = processSupervisor;
        this.signalParser = signalParser;
        this.retryQueueService = retryQueueService;
        this.paperTradingService = paperTradingService;
        this.accountingService = accountingService;
        this.pythonBridgeFacade = pythonBridgeFacade;
    }

    /**
     * Lanza la ejecución en tiempo real de una estrategia en un Virtual Thread.
     */
    public void ejecutarTradeEnTiempoReal(InstanciaEstrategia instancia, List<String> symbols) {
        Future<?> future = executor.submit(() -> runEngineRT(instancia, symbols));
        runtimeThreads.put(instancia.getId(), future);
        log.info("Hilo de ejecución lanzado para estrategia {}", instancia.getId());
    }

    /**
     * Supervisor de ejecución 24/7: mantiene la estrategia viva reiniciando el
     * proceso Python con backoff exponencial si cae de forma anómala, hasta que
     * (a) el usuario la detiene, (b) termina limpiamente, o (c) entra en un
     * crash-loop y se supera el máximo de reinicios (se aborta y liquida).
     */
    private void runEngineRT(InstanciaEstrategia instancia, List<String> symbols) {
        Long id = instancia.getId();
        stopRequested.remove(id);
        RestartBackoffPolicy backoff = new RestartBackoffPolicy(
                RESTART_BASE_DELAY_MS, RESTART_MAX_DELAY_MS, MAX_CONSECUTIVE_RESTARTS, STABLE_UPTIME_MS);
        boolean abortadoPorCrashLoop = false;

        while (!isStopRequested(id) && !Thread.currentThread().isInterrupted()) {
            long runStart = System.currentTimeMillis();
            boolean crashed = runOneProcess(instancia, symbols);
            long uptimeMs = System.currentTimeMillis() - runStart;

            if (isStopRequested(id) || Thread.currentThread().isInterrupted()) {
                break;                      // parada intencional durante la ejecución
            }
            if (!crashed) {
                break;                      // terminó por sí mismo sin error: no reiniciar en bucle
            }

            long delayMs = backoff.onRunEnded(uptimeMs);
            if (delayMs < 0) {
                abortadoPorCrashLoop = true;
                log.error("Estrategia {}: {} reinicios consecutivos sin estabilizar. Abortando y liquidando.",
                        id, backoff.consecutiveRestarts());
                break;
            }
            log.warn("Reiniciando estrategia {} en {} ms (reinicio consecutivo #{}, uptime previo {} ms)",
                    id, delayMs, backoff.consecutiveRestarts(), uptimeMs);
            if (!dormirInterrumpible(delayMs)) {
                break;                      // interrumpido durante el backoff
            }
        }

        gestionarCierre(instancia, abortadoPorCrashLoop);
    }

    /**
     * Ejecuta UNA instancia del proceso Python de principio a fin.
     *
     * @return {@code true} si terminó de forma anómala (candidato a reinicio);
     *         {@code false} si terminó limpiamente o por parada solicitada.
     */
    private boolean runOneProcess(InstanciaEstrategia instancia, List<String> symbols) {
        Process process = null;
        Future<?> stderrFuture = null;
        long startTime = System.currentTimeMillis();
        boolean crashed = false;

        try {
            process = processSupervisor.iniciarProcesoPython(instancia);

            stderrFuture = iniciarLecturaErroresPython(process, instancia);

            processSupervisor.enviarPayload(process, instancia, symbols);

            escucharSalidaPythonConTimeout(process, instancia, startTime);

            if (process.isAlive()) {
                boolean finished = process.waitFor(5, TimeUnit.SECONDS);
                if (!finished) {
                    log.warn("Proceso de estrategia {} aún vivo después de cierre, destruyendo",
                            instancia.getId());
                    processSupervisor.destroyProcessForcibly(process);
                }
            }

            int exitCode = process.exitValue();
            if (exitCode != 0 && !isStopRequested(instancia.getId()) && !Thread.currentThread().isInterrupted()) {
                crashed = true;
                log.warn("Proceso Python de estrategia {} terminó con código {}", instancia.getId(), exitCode);
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Hilo de estrategia {} interrumpido", instancia.getId());
        } catch (Exception e) {
            if (!isStopRequested(instancia.getId()) && !Thread.currentThread().isInterrupted()) {
                crashed = true;
                log.error("Error en motor Python ({}): {}", instancia.getNombreEstrategia(), e.getMessage(), e);
            }
        } finally {
            if (stderrFuture != null) {
                stderrFuture.cancel(true);
            }
            if (process != null) {
                processSupervisor.destroyProcessForcibly(process);
            }
        }
        return crashed;
    }

    private boolean isStopRequested(Long instanciaId) {
        return stopRequested.contains(instanciaId);
    }

    private boolean dormirInterrumpible(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Lee la salida de Python monitorizando liveness con un watchdog externo.
     *
     * <p>El bucle {@code readLine()} bloquea; por eso un watchdog independiente
     * vigila los timeouts de arranque e inactividad ({@link RealtimeActivityTracker})
     * y destruye el proceso si se cuelga, lo que desbloquea el {@code readLine()}.
     * Cada línea recibida (señal, log o heartbeat) cuenta como actividad.
     */
    private void escucharSalidaPythonConTimeout(Process process, InstanciaEstrategia instancia, long startTime)
            throws PythonProcessException {

        RealtimeActivityTracker tracker = new RealtimeActivityTracker(
                startTime, STARTUP_TIMEOUT_MS, INACTIVITY_TIMEOUT_MS);
        Future<?> watchdog = lanzarWatchdogInactividad(process, instancia, tracker);

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
                tracker.markActivity(System.currentTimeMillis());
                procesarLineaYSignal(line, instancia);
            }

            // EOF: si el watchdog mató el proceso, reportarlo como timeout, no como cierre normal.
            String reason = tracker.timedOutReason(System.currentTimeMillis());
            if (reason != null) {
                throw new PythonProcessException(
                        "Estrategia " + instancia.getId() + " detenida por watchdog: " + reason);
            }

        } catch (IOException e) {
            String reason = tracker.timedOutReason(System.currentTimeMillis());
            if (reason != null) {
                throw new PythonProcessException(
                        "Estrategia " + instancia.getId() + " detenida por watchdog: " + reason, e);
            }
            throw new PythonProcessException("Error al leer salida del proceso Python: " + e.getMessage(), e);
        } finally {
            watchdog.cancel(true);
        }
    }

    /**
     * Lanza un watchdog que destruye el proceso si supera el timeout de arranque
     * o de inactividad, permitiendo detectar procesos Python colgados en 24/7.
     */
    private Future<?> lanzarWatchdogInactividad(
            Process process, InstanciaEstrategia instancia, RealtimeActivityTracker tracker) {
        return executor.submit(() -> {
            try {
                while (process.isAlive() && !Thread.currentThread().isInterrupted()) {
                    Thread.sleep(WATCHDOG_POLL_MS);
                    String reason = tracker.timedOutReason(System.currentTimeMillis());
                    if (reason != null) {
                        log.warn("Watchdog: estrategia {} {} — destruyendo proceso Python",
                                instancia.getId(), reason);
                        processSupervisor.destroyProcessForcibly(process);
                        return;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /**
     * Procesa una línea del stdout: puede ser un log o una señal.
     */
    private void procesarLineaYSignal(String line, InstanciaEstrategia instancia) {
        SignalDTO signal = signalParser.parseLineaLog(line, instancia.getId());
        
        if (signal != null) {
            procesarSignalConReintento(instancia, signal);
        }
    }

    /**
     * Procesa una señal con lógica de reintento.
     */
    private void procesarSignalConReintento(InstanciaEstrategia instancia, SignalDTO signal) {
        try {
            paperTradingService.onSignal(instancia.getId(), signal);
            retryQueueService.resetFailureCount(instancia.getId());
            log.debug("Señal procesada correctamente: {} {}", signal.getAction(), signal.getSymbol());
            
        } catch (Exception e) {
            log.warn("Fallo al procesar señal (será reintentada): {} - Error: {}", 
                     signal.getSymbol(), e.getMessage());
            
            retryQueueService.enqueueFailedSignal(instancia.getId(), signal);
            
            // Verificar si se ha alcanzado el límite de fallos
            if (retryQueueService.incrementAndCheckFailureLimit(instancia.getId())) {
                throw new SignalProcessingException("Demasiados errores de señal. Estrategia detenida.", e);
            }
        }
    }

    /**
     * Lee stderr en paralelo para evitar bloqueo del proceso.
     */
    private Future<?> iniciarLecturaErroresPython(Process process, InstanciaEstrategia instancia) {
        return pythonBridgeFacade.drainStderrAsync(
                process,
                line -> log.warn("PYERR [{}]: {}", instancia.getNombreEstrategia(), line));
    }

    /**
     * Gestiona el cierre ordenado de una estrategia.
     */
    private void gestionarCierre(InstanciaEstrategia instancia, boolean errorCritico) {
        Long instanciaId = instancia.getId();
        
        // Procesar las últimas señales en cola
        try {
            retryQueueService.procesarSignalesPendientes(instanciaId, paperTradingService);
        } catch (Exception e) {
            log.warn("Advertencia al procesar cola pendiente: {}", e.getMessage());
        }
        
        if (errorCritico) {
            try {
                accountingService.closeStrategy(instancia.getWalletAsociada(), instancia.getId());
            } catch (Exception ex) {
                log.error("Fallo al liquidar estrategia: {}", ex.getMessage());
                detenerEstrategia(instancia.getId());
            }
        } else {
            detenerEstrategia(instancia.getId());
        }
        
        retryQueueService.cleanup(instanciaId);
        runtimeThreads.remove(instanciaId);
        stopRequested.remove(instanciaId);
    }

    /**
     * Detiene una estrategia específica. Marca la parada como intencional para
     * que el supervisor NO reinicie el proceso.
     */
    public void detenerEstrategia(Long instanciaId) {
        stopRequested.add(instanciaId);

        Future<?> future = runtimeThreads.remove(instanciaId);
        if (future != null) {
            future.cancel(true);
        }

        processSupervisor.destroyProcessForcibly(instanciaId);

        log.info("Recursos liberados para estrategia {}", instanciaId);
    }

    /**
     * Detiene todas las estrategias activas.
     */
    public void detenerTodo() {
        if (runtimeThreads.isEmpty()) {
            return;
        }

        log.info("Deteniendo {} procesos activos...", runtimeThreads.size());
        Set<Long> ids = new HashSet<>(runtimeThreads.keySet());
        ids.forEach(this::detenerEstrategia);
    }

    /**
     * Obtiene los IDs de estrategias activas.
     */
    public Set<Long> getIdsEstrategiasActivas() {
        return new HashSet<>(runtimeThreads.keySet());
    }

    /**
     * Hook de cierre al apagar la aplicación.
     */
    @PreDestroy
    public void apagarSistema() {
        log.info("Apagando coordinador de estrategias...");
        detenerTodo();
        executor.shutdownNow();
    }
}
