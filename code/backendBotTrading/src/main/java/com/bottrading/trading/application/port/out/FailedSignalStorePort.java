package com.bottrading.trading.application.port.out;

import java.util.List;

import com.bottrading.trading.infrastructure.bridge.SignalDTO;

/**
 * Puerto de salida para persistir las señales fallidas pendientes de reintento,
 * de modo que sobrevivan a un reinicio de la JVM (durabilidad de la cola).
 */
public interface FailedSignalStorePort {

    /** Persiste una señal fallida como pendiente de reintento. */
    void save(Long instanciaId, SignalDTO signal);

    /** Marca como procesada (soft-delete) una señal ya reintentada con éxito. */
    void markProcessed(Long instanciaId, SignalDTO signal);

    /** Descarta todas las pendientes de una instancia (al detener la estrategia). */
    void deleteAllForInstance(Long instanciaId);

    /** Carga las señales pendientes de una instancia (para recuperación al arranque). */
    List<SignalDTO> loadPending(Long instanciaId);

    /** IDs de instancia que tienen alguna señal pendiente persistida. */
    List<Long> instancesWithPending();
}
