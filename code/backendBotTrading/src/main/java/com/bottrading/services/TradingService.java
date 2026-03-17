package com.bottrading.services;

import com.bottrading.bridge.PythonBridgeExecutionException;
import com.bottrading.bridge.PythonBridgeFacade;
import com.bottrading.beans.InstanciaEstrategia;
import com.bottrading.beans.SignalDTO;
import com.bottrading.config.ProcessExecutorConfig;
import com.bottrading.exceptions.PythonProcessException;
import com.bottrading.exceptions.SignalProcessingException;
import com.bottrading.utils.PathConfig;
import com.bottrading.utils.PythonProcessSupport;
import com.google.gson.Gson;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.Queue;

/**
 * Servicio técnico de bajo nivel encargado de la gestión de procesos y hilos.
 * Administra el ciclo de vida de los scripts de Python (ejecución, comunicación
 * I/O y terminación).
 *
 * Mejoras en Fase 3:
 * - Timeouts inteligentes para procesos Python (90s init + 5m inactividad)
 * - Destrucción forzada de procesos en caso de timeout
 * - Constructor injection (no @Autowired)
 * - Resiliencia mejorada para colas de reintentos
 * - Logging a stdout en todos los motores para visibilidad de Java
 */
@Slf4j
@Service
public class TradingService {

    private final PaperTradingService paperTradingService;
    private final AccountingService accountingService;
    private final PythonBridgeFacade pythonBridgeFacade;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    // Mapa thread-safe para mantener referencias a los procesos en ejecución
    private final Map<Long, StrategyContext> procesosActivos = new ConcurrentHashMap<>();

    // Cola de señales fallidas para reintentos
    private final Map<Long, Queue<SignalDTO>> failedSignalsQueue = new ConcurrentHashMap<>();

    // Contador de fallos consecutivos por estrategia
    private final Map<Long, Integer> consecutiveFailures = new ConcurrentHashMap<>();
    private static final int MAX_CONSECUTIVE_FAILURES = 5;

    public TradingService(PaperTradingService paperTradingService,
            AccountingService accountingService,
            PythonBridgeFacade pythonBridgeFacade) {
        this.paperTradingService = paperTradingService;
        this.accountingService = accountingService;
        this.pythonBridgeFacade = pythonBridgeFacade;
    }

    /**
     * Clase interna para agrupar el proceso del sistema operativo y el hilo de Java
     * que lo monitoriza.
     */
    private static class StrategyContext {
        Process pythonProcess;
        Future<?> javaThread;

        public StrategyContext(Process pythonProcess, Future<?> javaThread) {
            this.pythonProcess = pythonProcess;
            this.javaThread = javaThread;
        }
    }

    /**
     * Lanza un nuevo hilo dedicado para una estrategia.
     * Gestiona la concurrencia para registrar el proceso correctamente en el mapa
     * de control.
     *
     * @param instancia La entidad de la estrategia.
     * @param symbols   Lista de símbolos a operar.
     */
    public void ejecutarTradeEnTiempoReal(InstanciaEstrategia instancia, List<String> symbols) {
        Future<?> future = executor.submit(() -> runEngineRT(instancia, symbols));
        procesosActivos.compute(instancia.getId(), (k, ctx) -> {
            if (ctx == null) {
                return new StrategyContext(null, future);
            } else {
                ctx.javaThread = future;
                return ctx;
            }
        });
    }

    /**
     * Lógica principal del hilo de ejecución.
     * Arranca el proceso Python, envía la configuración y escucha señales en bucle.
     * Incluye timeout y gestión mejorada de procesos.
     */
    private void runEngineRT(InstanciaEstrategia instancia, List<String> symbols) {
        boolean errorCritico = false;
        Process process = null;
        Future<?> stderrFuture = null;
        long startTime = System.currentTimeMillis();

        try {
            // 1. Iniciar proceso y registrarlo
            process = iniciarProcesoPython(instancia);

            // 2. Drenar stderr en paralelo para evitar bloqueo del proceso Python.
            stderrFuture = iniciarLecturaErroresPython(process, instancia);

            // 3. Enviar configuración
            enviarPayload(process, instancia, symbols);

            // 4. Bucle de escucha con timeout monitorizado
            escucharSalidaPythonConTimeout(process, instancia, startTime);

            // 5. Esperar cierre ordenado
            if (process.isAlive()) {
                boolean finished = process.waitFor(5, TimeUnit.SECONDS);
                if (!finished) {
                    log.warn("Proceso de estrategia {} aún vivo después de cierre, destruyendo", 
                            instancia.getId());
                    destroyProcessForcibly(process);
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
                destroyProcessForcibly(process);
            }
            gestionarCierre(instancia, errorCritico);
        }
    }

    /**
     * Lee la salida de Python con monitoring de timeout por INACTIVIDAD.
     * 
     * Estrategia:
     * - Para inicialización: permite hasta 90 segundos (el motor se conecta a Binance, carga estrategia, etc.)
     * - Para runtime: permite hasta 5 minutos SIN output (si no hay señales/logs, probablemente está muerto)
     * - Cada línea que se recibe resetea el contador de inactividad
     */
    private void escucharSalidaPythonConTimeout(Process process, InstanciaEstrategia instancia, long startTime) 
            throws PythonProcessException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {

            final long INIT_TIMEOUT_MS = 90_000;       // 90s para inicialización
            final long INACTIVITY_TIMEOUT_MS = 300_000; // 5m sin output
            long lastActivityTime = System.currentTimeMillis();
            boolean primeraActividad = true;

            String line;
            while ((line = reader.readLine()) != null) {
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
                
                long now = System.currentTimeMillis();
                long elapsedTotal = now - startTime;
                long elapsedInactivity = now - lastActivityTime;
                lastActivityTime = now; // Resetear inactividad al recibir línea
                
                // Solo verificar timeout de inicialización en la PRIMERA actividad
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
                
                procesarLineaLog(line, instancia);
            }
            
            // Si llegamos aquí, el motor cerró su stdout. Verificar si fue orderly o timeout de inactividad
            long finalInactivity = System.currentTimeMillis() - lastActivityTime;
            if (finalInactivity > INACTIVITY_TIMEOUT_MS) {
                log.warn("Estrategia {} excedió timeout de inactividad (5m sin output), terminando", 
                        instancia.getId());
                throw new PythonProcessException("Timeout de inactividad (>5m sin output)");
            }
            
        } catch (IOException e) {
            throw new PythonProcessException("Error al leer salida del proceso Python: " + e.getMessage(), e);
        }
    }

    // =========================================================================
    // MÉTODOS AUXILIARES (Divide y Vencerás)
    // =========================================================================

    private Process iniciarProcesoPython(InstanciaEstrategia instancia) throws PythonProcessException {
        String scriptPath;
        if (instancia.getNombreModelo() != null && !instancia.getNombreModelo().isEmpty()) {
            log.info("Arrancando Motor de IA para estrategia: {}", instancia.getNombreModelo());
            scriptPath = PathConfig.ENGINE_AI_RT_PATH; // Script para Modelos de ML
        } else {
            log.info("Arrancando Motor Estándar para estrategia: {}", instancia.getNombreEstrategia());
            scriptPath = PathConfig.ENGINE_RT_PATH; // Script normal
        }

        try {
            Process process = pythonBridgeFacade.startProcess(
                    "trading-rt-" + instancia.getId(),
                    scriptPath,
                    false,
                    Collections.emptyList());

            procesosActivos.compute(instancia.getId(), (k, ctx) -> {
                if (ctx == null)
                    return new StrategyContext(process, null);
                ctx.pythonProcess = process;
                return ctx;
            });

            return process;
        } catch (PythonBridgeExecutionException e) {
            throw new PythonProcessException("Fallo al iniciar proceso Python: " + e.getMessage(), e);
        }
    }

    private Future<?> iniciarLecturaErroresPython(Process process, InstanciaEstrategia instancia) {
        return pythonBridgeFacade.drainStderrAsync(
                process,
                line -> log.warn("PYERR [{}]: {}", instancia.getNombreEstrategia(), line));
    }

    private void procesarLineaLog(String line, InstanciaEstrategia instancia) {
        String trimmedLine = line.trim();

        if (trimmedLine.startsWith("SIGNAL\t")) {
            procesarParseJsonSignal(trimmedLine.substring("SIGNAL\t".length()), instancia);
        } else {
            // Log normal de Python
            log.info("PYLOG [{}]: {}", instancia.getNombreEstrategia(), line);
        }
    }

    /**
     * Destruye un proceso forzosamente y registra el evento.
     */
    private void destroyProcessForcibly(Process process) {
        if (process != null && process.isAlive()) {
            log.warn("Destruyendo proceso Python forzadamente");
            pythonBridgeFacade.destroyProcess(process);
        }
    }

    private void procesarParseJsonSignal(String line, InstanciaEstrategia instancia) {
        try {
            SignalDTO signal = new Gson().fromJson(line, SignalDTO.class);
            procesarSignalConReintento(instancia, signal);
        } catch (com.google.gson.JsonSyntaxException e) {
            log.error("JSON corrupto de Python (será ignorado): {} - Error: {}", line, e.getMessage());
        } catch (SignalProcessingException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error crítico procesando línea: {}", e.getMessage(), e);
            throw new SignalProcessingException("Error irrecuperable en procesamiento de señal", e);
        }
    }

    /**
     * Procesa una señal con reintento automático en caso de fallo.
     */
    private void procesarSignalConReintento(InstanciaEstrategia instancia, SignalDTO signal) {
        try {
            paperTradingService.onSignal(instancia.getId(), signal);
            // Reset contador si tuvo éxito
            consecutiveFailures.put(instancia.getId(), 0);
            log.debug("Señal procesada correctamente: {} {}", signal.getAction(), signal.getSymbol());
            
        } catch (Exception e) {
            // Si falla, encolar para reintentos
            log.warn("Fallo al procesar señal (será reintentada): {} - Error: {}", 
                     signal.getSymbol(), e.getMessage());
            
            enqueueFailedSignal(instancia.getId(), signal);
            
            // Monitoreo de fallos consecutivos
            int failCount = consecutiveFailures.getOrDefault(instancia.getId(), 0) + 1;
            consecutiveFailures.put(instancia.getId(), failCount);
            
            // Si hay demasiados fallos, detener la estrategia
            if (failCount >= MAX_CONSECUTIVE_FAILURES) {
                log.error("❌ {} fallos consecutivos en estrategia {}. Deteniendo...", 
                          MAX_CONSECUTIVE_FAILURES, instancia.getId());
                throw new SignalProcessingException("Demasiados errores de señal. Estrategia detenida.");
            }
        }
    }

    /**
     * Encola una señal fallida para reintentarla más tarde.
     */
    private void enqueueFailedSignal(Long instanciaId, SignalDTO signal) {
        failedSignalsQueue
            .computeIfAbsent(instanciaId, k -> new LinkedBlockingQueue<>())
            .offer(signal);
    }

    /**
     * Procesa la cola de señales fallidas (reintentos).
     * Se puede ser llamado periódicamente o al terminar la estrategia.
     */
    public void procesarSignalesPendientes(Long instanciaId) {
        Queue<SignalDTO> cola = failedSignalsQueue.get(instanciaId);
        if (cola == null || cola.isEmpty()) {
            return;
        }

        int pendientesIniciales = cola.size();
        log.info("Procesando {} señales en cola para estrategia {}", pendientesIniciales, instanciaId);

        // Procesa solo el lote inicial para evitar bucles infinitos si una señal falla
        // de forma persistente.
        for (int i = 0; i < pendientesIniciales; i++) {
            SignalDTO signal = cola.poll();
            if (signal == null) {
                break;
            }
            try {
                paperTradingService.onSignal(instanciaId, signal);
                consecutiveFailures.put(instanciaId, 0);
                log.info("✅ Señal de reintento procesada: {} {}", signal.getAction(), signal.getSymbol());
            } catch (Exception e) {
                log.warn("⚠️ Reintento fallido, volviendo a encolar: {} - Error: {}", 
                        signal.getSymbol(), e.getMessage());
                cola.offer(signal); // Reintentar más tarde
            }
        }

        if (!cola.isEmpty()) {
            log.warn("Quedan {} señales pendientes tras el ciclo de reintentos para estrategia {}", cola.size(), instanciaId);
        }
    }

    private void gestionarCierre(InstanciaEstrategia instancia, boolean errorCritico) {
        Long instanciaId = instancia.getId();
        
        // Procesar las últimas señales en cola antes de cerrar
        try {
            procesarSignalesPendientes(instanciaId);
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
        
        // Limpiar colas y errores
        failedSignalsQueue.remove(instanciaId);
        consecutiveFailures.remove(instanciaId);
    }

    /**
     * Serializa y envía la configuración inicial al script de Python a través de STDIN.
     */
    private void enviarPayload(Process p, InstanciaEstrategia inst, List<String> symbols) throws PythonProcessException {
        Map<String, Object> payload = new HashMap<>();

        if (inst.getNombreEstrategia() != null && !inst.getNombreEstrategia().isBlank()) {
            payload.put("strategy_path", PathConfig.getValidStrategyPath(inst.getNombreEstrategia()));
        }
        payload.put("symbols", new ArrayList<>(symbols));
        payload.put("timeframe", inst.getTimeframe());
        payload.put("capital", inst.getCapitalReservado());

        // Agregar modelo si existe (para estrategias de IA)
        if (inst.getNombreModelo() != null && !inst.getNombreModelo().isEmpty()) {
            payload.put("model_name", inst.getNombreModelo());
        }

        try (OutputStream os = p.getOutputStream()) {
            PythonProcessSupport.writeUtf8(os, new Gson().toJson(payload));
        } catch (IOException e) {
            throw new PythonProcessException("Error al enviar configuración a Python: " + e.getMessage(), e);
        }
    }

    /**
     * Detiene una estrategia específica.
     * Cancela el hilo de Java y mata forzosamente el proceso de Python.
     *
     * @param instanciaId ID de la estrategia a detener.
     */
    public void detenerEstrategia(Long instanciaId) {
        StrategyContext ctx = procesosActivos.remove(instanciaId);

        if (ctx != null) {
            // 1. Interrumpir lectura del stream
            if (ctx.javaThread != null) {
                ctx.javaThread.cancel(true);
            }

            // 2. Destruir proceso del sistema operativo de manera segura
            if (ctx.pythonProcess != null) {
                destroyProcessForcibly(ctx.pythonProcess);
            }

            log.info("TradingService: Recursos liberados para ID {}", instanciaId);
        }
    }

    /**
     * Detiene todas las estrategias activas sin apagar el servicio.
     * Útil para comandos masivos o logout.
     */
    public void detenerTodo() {
        if (procesosActivos.isEmpty())
            return;

        log.info("Deteniendo " + procesosActivos.size() + " procesos activos...");
        Set<Long> ids = new HashSet<>(procesosActivos.keySet());
        ids.forEach(this::detenerEstrategia);
    }

    /**
     * Obtiene el conjunto de IDs de las estrategias que están corriendo
     * actualmente.
     * @return Set de IDs.
     */
    public Set<Long> getIdsEstrategiasActivas() {
        return new HashSet<>(procesosActivos.keySet());
    }

    /**
     * Hook de ciclo de vida para limpieza final al apagar la aplicación Spring
     * Boot.
     */
    @PreDestroy
    public void apagarSistema() {
        log.info("Apagando sistema de trading...");
        detenerTodo();
        executor.shutdownNow();
    }
}