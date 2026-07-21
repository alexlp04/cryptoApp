package com.bottrading.trading.infrastructure.bridge;

import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import com.bottrading.trading.application.port.in.ProcessSignalUseCase;
import com.bottrading.trading.application.port.out.FailedSignalStorePort;

import lombok.extern.slf4j.Slf4j;

/**
 * Gestor de colas de reintentos para señales fallidas.
 * Mantiene un registro de fallos consecutivos y encola señales para reintentos.
 *
 * <p>La cola en memoria se respalda en un {@link FailedSignalStorePort} para que
 * las señales pendientes sobrevivan a un reinicio de la JVM: se persisten al
 * encolar, se marcan como procesadas al reintentarse con éxito y se recuperan al
 * arrancar la aplicación.
 *
 * Responsabilidad única: gestión de reintentos de señales fallidas.
 */
@Slf4j
@Service
public class SignalRetryQueueService {

    private static final int MAX_CONSECUTIVE_FAILURES = 5;

    private final FailedSignalStorePort failedSignalStore;

    // Cola de señales fallidas por estrategia (cache en memoria respaldada por el store)
    private final Map<Long, Queue<SignalDTO>> failedSignalsQueue = new ConcurrentHashMap<>();

    private final Map<Long, Integer> consecutiveFailures = new ConcurrentHashMap<>();

    public SignalRetryQueueService(FailedSignalStorePort failedSignalStore) {
        this.failedSignalStore = failedSignalStore;
    }

    /**
     * Recupera las señales pendientes persistidas y las recarga en memoria tras
     * arrancar la aplicación, de modo que un reinicio de la JVM no las pierda.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recuperarPendientes() {
        List<Long> instancias = failedSignalStore.instancesWithPending();
        for (Long instanciaId : instancias) {
            List<SignalDTO> pendientes = failedSignalStore.loadPending(instanciaId);
            pendientes.forEach(s -> offerEnMemoria(instanciaId, s));
            if (!pendientes.isEmpty()) {
                log.info("Recuperadas {} señales pendientes de estrategia {} tras reinicio",
                        pendientes.size(), instanciaId);
            }
        }
    }

    /**
     * Encola una señal fallida para reintentarla más tarde (y la persiste).
     */
    public void enqueueFailedSignal(Long instanciaId, SignalDTO signal) {
        offerEnMemoria(instanciaId, signal);
        failedSignalStore.save(instanciaId, signal);
        log.warn("Señal encolada para reintento: {} {}", signal.getSymbol(), signal.getAction());
    }

    private void offerEnMemoria(Long instanciaId, SignalDTO signal) {
        failedSignalsQueue
            .computeIfAbsent(instanciaId, k -> new LinkedBlockingQueue<>())
            .offer(signal);
    }

    /** IDs de instancia con señales pendientes en memoria (para el drenado periódico). */
    public Set<Long> instanciasConPendientes() {
        return failedSignalsQueue.entrySet().stream()
                .filter(e -> !e.getValue().isEmpty())
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * Incrementa el contador de fallos consecutivos.
     * Retorna true si se ha alcanzado el límite máximo.
     */
    public boolean incrementAndCheckFailureLimit(Long instanciaId) {
        int failCount = consecutiveFailures.getOrDefault(instanciaId, 0) + 1;
        consecutiveFailures.put(instanciaId, failCount);
        
        if (failCount >= MAX_CONSECUTIVE_FAILURES) {
            log.error("❌ {} fallos consecutivos en estrategia {}. Límite alcanzado.", 
                     MAX_CONSECUTIVE_FAILURES, instanciaId);
            return true;
        }
        return false;
    }

    /**
     * Reinicia el contador de fallos para una estrategia.
     */
    public void resetFailureCount(Long instanciaId) {
        consecutiveFailures.put(instanciaId, 0);
    }

    /**
     * Procesa toda la cola de señales fallidas para una estrategia.
     * Intenta reintentar cada señal una sola vez en el ciclo actual.
     */
    public void procesarSignalesPendientes(Long instanciaId, ProcessSignalUseCase paperTradingService) {
        Queue<SignalDTO> cola = failedSignalsQueue.get(instanciaId);
        if (cola == null || cola.isEmpty()) {
            return;
        }

        int pendientesIniciales = cola.size();
        log.info("Procesando {} señales en cola de reintentos para estrategia {}", 
                 pendientesIniciales, instanciaId);

        // Procesa solo el lote inicial para evitar bucles infinitos
        for (int i = 0; i < pendientesIniciales; i++) {
            SignalDTO signal = cola.poll();
            if (signal == null) {
                break;
            }
            try {
                paperTradingService.onSignal(instanciaId, signal);
                failedSignalStore.markProcessed(instanciaId, signal);
                resetFailureCount(instanciaId);
                log.info("✅ Señal de reintento procesada: {} {}", signal.getAction(), signal.getSymbol());
            } catch (Exception e) {
                log.warn("⚠️ Reintento fallido, volviendo a encolar: {} - Error: {}", 
                        signal.getSymbol(), e.getMessage());
                cola.offer(signal);  // Reintentar más tarde
            }
        }

        if (!cola.isEmpty()) {
            log.warn("Quedan {} señales pendientes tras el ciclo de reintentos para estrategia {}", 
                    cola.size(), instanciaId);
        }
    }

    /**
     * Limpia los datos de reintento para una estrategia (al terminarla).
     */
    public void cleanup(Long instanciaId) {
        failedSignalsQueue.remove(instanciaId);
        consecutiveFailures.remove(instanciaId);
        failedSignalStore.deleteAllForInstance(instanciaId);
        log.debug("Colas de reintento limpiadas para estrategia {}", instanciaId);
    }

    /**
     * Obtiene el número de señales pendientes para una estrategia.
     */
    public int getPendingSignalCount(Long instanciaId) {
        Queue<SignalDTO> cola = failedSignalsQueue.get(instanciaId);
        return cola != null ? cola.size() : 0;
    }
}
