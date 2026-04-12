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
     * Lee la salida de Python con monitoring de timeout por INACTIVIDAD.
     */
    private void escucharSalidaPythonConTimeout(Process process, InstanciaEstrategia instancia, long startTime) 
            throws PythonProcessException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {

            final long INIT_TIMEOUT_MS = 90_000;        // 90s para inicialización
            boolean primeraActividad = true;

            String line;
            while ((line = reader.readLine()) != null) {
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
                
                long now = System.currentTimeMillis();
                long elapsedTotal = now - startTime;
                // Verificar timeout de inicialización
                if (primeraActividad && elapsedTotal > INIT_TIMEOUT_MS) {
                    log.warn("Estrategia {} excedió timeout de inicialización (90s), terminando", 
                            instancia.getId());
                    throw new PythonProcessException("Timeout de inicialización excedido (>90s)");
                }
                
                if (primeraActividad) {
                    primeraActividad = false;
                    log.info("Estrategia {} superó inicialización, pasada a monitoreo de inactividad (5m)", 
                            instancia.getId());
                }
                
                // Procesar línea (parsear y procesar signals si aplica)
                procesarLineaYSignal(line, instancia);
            }
            
        } catch (IOException e) {
            throw new PythonProcessException("Error al leer salida del proceso Python: " + e.getMessage(), e);
        }
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
