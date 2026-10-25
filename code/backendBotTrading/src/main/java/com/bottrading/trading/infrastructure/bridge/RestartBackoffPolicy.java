package com.bottrading.trading.infrastructure.bridge;

/**
 * Política de reinicio con backoff exponencial y protección anti crash-loop
 * para la supervisión de estrategias en tiempo real (24/7).
 *
 * <p>Reglas:
 * <ul>
 *   <li>El retraso entre reinicios crece exponencialmente ({@code base·2^(n-1)})
 *       hasta un tope ({@code maxDelay}).</li>
 *   <li>Una ejecución que se mantuvo "estable" (uptime ≥ {@code stableUptime})
 *       resetea el contador: un fallo puntual tras horas sanas se trata como el
 *       primer reinicio, no como parte de un bucle de caídas.</li>
 *   <li>Tras superar {@code maxConsecutiveRestarts} reinicios sin estabilizar,
 *       {@link #onRunEnded(long)} devuelve {@code -1}: hay que abortar la
 *       estrategia en lugar de reiniciarla eternamente.</li>
 * </ul>
 *
 * <p>No es thread-safe: cada estrategia usa su propia instancia dentro de un
 * único hilo supervisor. Clase pura (sin reloj ni proceso) para poder testearla.
 */
public final class RestartBackoffPolicy {

    private final long baseDelayMs;
    private final long maxDelayMs;
    private final int maxConsecutiveRestarts;
    private final long stableUptimeMs;

    private int consecutiveRestarts;

    public RestartBackoffPolicy(long baseDelayMs, long maxDelayMs, int maxConsecutiveRestarts, long stableUptimeMs) {
        if (baseDelayMs <= 0 || maxDelayMs < baseDelayMs) {
            throw new IllegalArgumentException("Se requiere 0 < baseDelayMs <= maxDelayMs");
        }
        if (maxConsecutiveRestarts <= 0 || stableUptimeMs <= 0) {
            throw new IllegalArgumentException("maxConsecutiveRestarts y stableUptimeMs deben ser positivos");
        }
        this.baseDelayMs = baseDelayMs;
        this.maxDelayMs = maxDelayMs;
        this.maxConsecutiveRestarts = maxConsecutiveRestarts;
        this.stableUptimeMs = stableUptimeMs;
    }

    /**
     * Registra el fin de una ejecución que corrió {@code uptimeMs} y devuelve el
     * retraso (ms) a esperar antes del próximo reinicio, o {@code -1} si se debe
     * abortar por superar el máximo de reinicios consecutivos.
     */
    public long onRunEnded(long uptimeMs) {
        if (uptimeMs >= stableUptimeMs) {
            consecutiveRestarts = 0;
        }
        consecutiveRestarts++;
        if (consecutiveRestarts > maxConsecutiveRestarts) {
            return -1L;
        }
        int shift = Math.min(consecutiveRestarts - 1, 20);
        long delay = baseDelayMs << shift;
        if (delay <= 0 || delay > maxDelayMs) {   // guarda de overflow / tope
            delay = maxDelayMs;
        }
        return delay;
    }

    public int consecutiveRestarts() {
        return consecutiveRestarts;
    }

    public void reset() {
        consecutiveRestarts = 0;
    }
}
