package com.bottrading.trading.infrastructure.bridge;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Rastrea la actividad del canal RT de un proceso Python para detectar cuelgues.
 */
public final class RealtimeActivityTracker {

    private final long startTimeMs;
    private final AtomicLong lastActivityMs;
    private final AtomicBoolean firstActivitySeen = new AtomicBoolean(false);
    private final long startupTimeoutMs;
    private final long inactivityTimeoutMs;

    public RealtimeActivityTracker(long nowMs, long startupTimeoutMs, long inactivityTimeoutMs) {
        if (startupTimeoutMs <= 0 || inactivityTimeoutMs <= 0) {
            throw new IllegalArgumentException("Los timeouts deben ser positivos");
        }
        this.startTimeMs = nowMs;
        this.lastActivityMs = new AtomicLong(nowMs);
        this.startupTimeoutMs = startupTimeoutMs;
        this.inactivityTimeoutMs = inactivityTimeoutMs;
    }

    /** Registra actividad (cualquier línea recibida por stdout: señal, log o heartbeat). */
    public void markActivity(long nowMs) {
        lastActivityMs.set(nowMs);
        firstActivitySeen.set(true);
    }

    public boolean hasFirstActivity() {
        return firstActivitySeen.get();
    }

    /** si aún no hubo ninguna salida y se superó el plazo de arranque. */
    public boolean isStartupTimedOut(long nowMs) {
        return !firstActivitySeen.get() && (nowMs - startTimeMs) > startupTimeoutMs;
    }

    /** si ya hubo actividad pero lleva demasiado tiempo en silencio. */
    public boolean isInactivityTimedOut(long nowMs) {
        return firstActivitySeen.get() && (nowMs - lastActivityMs.get()) > inactivityTimeoutMs;
    }

    /**
     * Devuelve el motivo del timeout (para logs y mensajes de excepción) o
     * si el proceso sigue considerándose vivo.
     */
    public String timedOutReason(long nowMs) {
        if (isStartupTimedOut(nowMs)) {
            return "sin salida inicial en " + startupTimeoutMs + " ms (timeout de arranque)";
        }
        if (isInactivityTimedOut(nowMs)) {
            return "sin actividad durante " + (nowMs - lastActivityMs.get())
                    + " ms, límite " + inactivityTimeoutMs + " ms (timeout de inactividad)";
        }
        return null;
    }
}
