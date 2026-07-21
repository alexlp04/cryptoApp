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

    private final Map<Long, Future<?>> runtimeThreads = new ConcurrentHashMap<>();

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
     * Lógica principal del flujo de ejecución en tiempo real.
     */
    private void runEngineRT(InstanciaEstrategia instancia, List<String> symbols) {
        boolean errorCritico = false;
        Process process = null;
        Future<?> stderrFuture = null;
        long startTime = System.currentTimeMillis();

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
            if (exitCode != 0 && !Thread.currentThread().isInterrupted()) {
                throw new PythonProcessException("Proceso Python terminó con código: " + exitCode);
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Hilo de estrategia {} interrumpido", instancia.getId());
        } catch (Exception e) {
            if (!Thread.currentThread().isInterrupted()) {
                errorCritico = true;
                log.error("Error en motor Python ({}): {}", instancia.getNombreEstrategia(), e.getMessage(), e);
            }
        } finally {
            if (stderrFuture != null) {
                stderrFuture.cancel(true);
            }
            if (process != null) {
                processSupervisor.destroyProcessForcibly(process);
            }
            gestionarCierre(instancia, errorCritico);
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
    }

    /**
     * Detiene una estrategia específica.
     */
    public void detenerEstrategia(Long instanciaId) {
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
