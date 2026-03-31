package com.bottrading.infrastructure.bridge;

import com.bottrading.beans.SignalDTO;
import com.bottrading.application.trading.PaperTradingService;
import com.bottrading.exceptions.SignalProcessingException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Gestor de colas de reintentos para señales fallidas.
 * Mantiene un registro de fallos consecutivos y encola señales para reintentos.
 * 
 * Responsabilidad única: gestión de reintentos de señales fallidas.
 */
@Slf4j
@Service
public class SignalRetryQueueService {

    private static final int MAX_CONSECUTIVE_FAILURES = 5;

    // Cola de señales fallidas por estrategia
    private final Map<Long, Queue<SignalDTO>> failedSignalsQueue = new ConcurrentHashMap<>();
    
    // Contador de fallos consecutivos por estrategia
    private final Map<Long, Integer> consecutiveFailures = new ConcurrentHashMap<>();

    /**
     * Encola una señal fallida para reintentarla más tarde.
     */
    public void enqueueFailedSignal(Long instanciaId, SignalDTO signal) {
        failedSignalsQueue
            .computeIfAbsent(instanciaId, k -> new LinkedBlockingQueue<>())
            .offer(signal);
        log.warn("Señal encolada para reintento: {} {}", signal.getSymbol(), signal.getAction());
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
    public void procesarSignalesPendientes(Long instanciaId, PaperTradingService paperTradingService) {
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
