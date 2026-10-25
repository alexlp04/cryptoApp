package com.bottrading.trading.infrastructure.bridge;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Registro de señales ya aplicadas con éxito, por instancia de estrategia, para
 * garantizar idempotencia: una misma señal (reentregada o reintentada) no se
 * aplica dos veces.
 *
 * <p>Cada instancia mantiene un conjunto ACOTADO de claves de idempotencia con
 * desalojo FIFO ({@code maxKeysPerInstance}), de modo que la memoria no crece
 * sin límite en operación 24/7 (las velas antiguas no se reentregan).
 *
 * <p>Thread-safe. El patrón de uso es "comprobar antes de aplicar, marcar tras
 * aplicar con éxito": ver {@code PaperTradingService#onSignal}.
 */
@Component
public final class ProcessedSignalRegistry {

    private static final int DEFAULT_MAX_KEYS_PER_INSTANCE = 512;

    private final int maxKeysPerInstance;
    private final Map<Long, Set<String>> processedByInstance = new ConcurrentHashMap<>();

    public ProcessedSignalRegistry() {
        this(DEFAULT_MAX_KEYS_PER_INSTANCE);
    }

    public ProcessedSignalRegistry(int maxKeysPerInstance) {
        if (maxKeysPerInstance <= 0) {
            throw new IllegalArgumentException("maxKeysPerInstance debe ser positivo");
        }
        this.maxKeysPerInstance = maxKeysPerInstance;
    }

    /** {@code true} si la señal (por su clave) ya se aplicó con éxito antes. */
    public boolean seen(Long instanciaId, String idempotencyKey) {
        Set<String> keys = processedByInstance.get(instanciaId);
        return keys != null && keys.contains(idempotencyKey);
    }

    /** Marca la señal como aplicada con éxito. Idempotente. */
    public void mark(Long instanciaId, String idempotencyKey) {
        processedByInstance
                .computeIfAbsent(instanciaId, id -> newBoundedSet())
                .add(idempotencyKey);
    }

    /** Libera el registro de una instancia (al detener la estrategia). */
    public void cleanup(Long instanciaId) {
        processedByInstance.remove(instanciaId);
    }

    /** Número de claves recordadas para una instancia (para tests/observabilidad). */
    public int size(Long instanciaId) {
        Set<String> keys = processedByInstance.get(instanciaId);
        return keys == null ? 0 : keys.size();
    }

    private Set<String> newBoundedSet() {
        Map<String, Boolean> lru = new LinkedHashMap<>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > maxKeysPerInstance;
            }
        };
        return Collections.synchronizedSet(Collections.newSetFromMap(lru));
    }
}
