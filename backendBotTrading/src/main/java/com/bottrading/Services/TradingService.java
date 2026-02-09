package com.bottrading.services;

import com.bottrading.beans.InstanciaEstrategia;
import com.bottrading.beans.SignalDTO;
import com.bottrading.utils.PathConfig;
import com.google.gson.Gson;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
        // Enviar tarea al pool de hilos
        Future<?> future = executor.submit(() -> runEngineRT(instancia, symbols));

        // Registro atómico en el mapa
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
            // 1. Iniciar proceso y registrarlo
            process = iniciarProcesoPython(instancia);

            // 2. Enviar configuración
            enviarPayload(process, instancia, symbols);

            // 3. Bucle de escucha (Bloqueante)
            escucharSalidaPython(process, instancia);

            // 4. Esperar cierre ordenado
            int exitCode = process.waitFor();
            if (exitCode != 0 && !Thread.currentThread().isInterrupted()) {
                throw new RuntimeException("Proceso Python terminó con código: " + exitCode);
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

    private Process iniciarProcesoPython(InstanciaEstrategia instancia) throws IOException {
        ProcessBuilder pb = new ProcessBuilder("python", PathConfig.ENGINE_RT_PATH);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        procesosActivos.compute(instancia.getId(), (k, ctx) -> {
            if (ctx == null) return new StrategyContext(process, null);
            ctx.pythonProcess = process;
            return ctx;
        });
        
        return process;
    }

    private void escucharSalidaPython(Process process, InstanciaEstrategia instancia) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
                procesarLineaLog(line, instancia);
            }
        }
    }

    private void procesarLineaLog(String line, InstanciaEstrategia instancia) {
        if (line.trim().startsWith("{")) {
            try {
                SignalDTO signal = gson.fromJson(line, SignalDTO.class);
                paperTradingService.onSignal(instancia.getId(), signal);
            } catch (Exception e) {
                log.error("JSON corrupto de Python: {}", line);
            }
        } else {
            log.info("LOG [{}]: {}", instancia.getNombreEstrategia(), line);
        }
    }

    private void gestionarCierre(InstanciaEstrategia instancia, boolean errorCritico) {
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
    }


    /**
     * Serializa y envía la configuración inicial al script de Python a través de
     * STDIN.
     */
    private void enviarPayload(Process p, InstanciaEstrategia inst, List<String> symbols) throws IOException {
        Map<String, Object> payload = Map.of(
                "strategy_path", PathConfig.getValidStrategyPath(inst.getNombreEstrategia()),
                "symbols", new ArrayList<>(symbols),
                "timeframe", inst.getTimeframe(),
                "capital", inst.getCapitalReservado());

        try (OutputStream os = p.getOutputStream()) {
            os.write(gson.toJson(payload).getBytes(StandardCharsets.UTF_8));
            os.flush();
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
     * 
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