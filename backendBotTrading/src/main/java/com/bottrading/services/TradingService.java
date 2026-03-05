package com.bottrading.services;

import com.bottrading.beans.InstanciaEstrategia;
import com.bottrading.beans.SignalDTO;
import com.bottrading.exceptions.PythonProcessException;
import com.bottrading.exceptions.SignalProcessingException;
import com.bottrading.utils.AppConstants;
import com.bottrading.utils.PathConfig;
import com.google.gson.Gson;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
import java.util.Queue;

/**
 * Servicio técnico de bajo nivel encargado de la gestión de procesos y hilos.
 * Administra el ciclo de vida de los scripts de Python (ejecución, comunicación
 * I/O y terminación).
 */
@Slf4j
@Service
public class TradingService {

    private final PaperTradingService paperTradingService;
    private final AccountingService accountingService;

    private final Gson gson = new Gson();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    // Mapa thread-safe para mantener referencias a los procesos en ejecución
    private final Map<Long, StrategyContext> procesosActivos = new ConcurrentHashMap<>();
    
    // Cola de señales fallidas para reintentos (Long = instanciaId, SignalDTO = signal)
    private final Map<Long, Queue<SignalDTO>> failedSignalsQueue = new ConcurrentHashMap<>();
    
    // Contador de fallos consecutivos por estrategia
    private final Map<Long, Integer> consecutiveFailures = new ConcurrentHashMap<>();
    private static final int MAX_CONSECUTIVE_FAILURES = 5;

    @Autowired
    public TradingService(PaperTradingService paperTradingService, AccountingService accountingService) {
        this.paperTradingService = paperTradingService;
        this.accountingService = accountingService;
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
     */
    private void runEngineRT(InstanciaEstrategia instancia, List<String> symbols) {
        boolean errorCritico = false;
        Process process = null;

        try {
            // 1. Iniciar proceso y registrarlo (ELIGE EL SCRIPT CORRECTO AQUÍ)
            process = iniciarProcesoPython(instancia);

            // 2. Enviar configuración
            enviarPayload(process, instancia, symbols);

            // 3. Bucle de escucha (Bloqueante)
            escucharSalidaPython(process, instancia);

            // 4. Esperar cierre ordenado
            int exitCode = process.waitFor();
            if (exitCode != 0 && !Thread.currentThread().isInterrupted()) {
                throw new PythonProcessException("Proceso Python terminó con código: " + exitCode);
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            if (!Thread.currentThread().isInterrupted()) {
                errorCritico = true;
                log.error("Error en motor Python ({}): {}", instancia.getNombreEstrategia(), e.getMessage());
            }
        } finally {
            gestionarCierre(instancia, errorCritico);
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
            ProcessBuilder pb = new ProcessBuilder(AppConstants.PYTHON_EXECUTABLE, scriptPath);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            procesosActivos.compute(instancia.getId(), (k, ctx) -> {
                if (ctx == null)
                    return new StrategyContext(process, null);
                ctx.pythonProcess = process;
                return ctx;
            });

            return process;
        } catch (IOException e) {
            throw new PythonProcessException("Fallo al iniciar proceso Python: " + e.getMessage(), e);
        }
    }

    private void escucharSalidaPython(Process process, InstanciaEstrategia instancia) throws PythonProcessException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
                procesarLineaLog(line, instancia);
            }
        } catch (IOException e) {
            throw new PythonProcessException("Error al leer salida del proceso Python: " + e.getMessage(), e);
        }
    }

    private void procesarLineaLog(String line, InstanciaEstrategia instancia) {
        if (line.trim().startsWith("{")) {
            procesarParseJsonSignal(line, instancia);
        } else {
            // Log normal de Python
            log.info("LOG [{}]: {}", instancia.getNombreEstrategia(), line);
        }
    }

    private void procesarParseJsonSignal(String line, InstanciaEstrategia instancia) {
        try {
            SignalDTO signal = gson.fromJson(line, SignalDTO.class);
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

        log.info("Procesando {} señales en cola para estrategia {}", cola.size(), instanciaId);
        
        while (!cola.isEmpty()) {
            SignalDTO signal = cola.poll();
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
     * Serializa y envía la configuración inicial al script de Python a través de
     * STDIN.
     */
    private void enviarPayload(Process p, InstanciaEstrategia inst, List<String> symbols) throws PythonProcessException {
        // Usamos un HashMap normal en lugar de Map.of para poder añadir claves condicionalmente
        Map<String, Object> payload = new HashMap<>();
        
        payload.put("strategy_path", PathConfig.getValidStrategyPath(inst.getNombreEstrategia()));
        payload.put("symbols", new ArrayList<>(symbols));
        payload.put("timeframe", inst.getTimeframe());
        payload.put("capital", inst.getCapitalReservado());

        // 🔥 AÑADIMOS EL MODELO AL PAYLOAD SI EXISTE
        if (inst.getNombreModelo() != null && !inst.getNombreModelo().isEmpty()) {
            payload.put("model_name", inst.getNombreModelo());
        }

        try (OutputStream os = p.getOutputStream()) {
            os.write(gson.toJson(payload).getBytes(StandardCharsets.UTF_8));
            os.flush();
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

            // 2. Matar proceso del sistema operativo
            if (ctx.pythonProcess != null && ctx.pythonProcess.isAlive()) {
                ctx.pythonProcess.destroyForcibly();
            }

            log.info("TradingService: Recursos liberados para ID " + instanciaId);
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