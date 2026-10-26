package com.bottrading.strategy.application.port.in;

import java.math.BigDecimal;
import java.util.List;

/**
 * Puerto de entrada para gestión del ciclo de vida de estrategias.
 */
public interface StrategyLifecycleUseCase {

    /** Igual que la variante completa, evaluando la señal al cierre de vela. */
    @SuppressWarnings("java:S107")
    default void iniciarTradeRT(String nombreEstra, String nombreModelo, String tf, List<String> coins,
                                boolean isReal, Long walletId, BigDecimal risk, BigDecimal capital,
                                Long usuarioId) {
        iniciarTradeRT(nombreEstra, nombreModelo, tf, coins, isReal, walletId, risk, capital, "close", usuarioId);
    }

    /**
     * signalMode: "close" (al cerrar la vela), "open" (al abrir la siguiente) o "both".
     * usuarioId: dueño de la estrategia.
     */
    @SuppressWarnings("java:S107")
    void iniciarTradeRT(String nombreEstra, String nombreModelo, String tf, List<String> coins,
                        boolean isReal, Long walletId, BigDecimal risk, BigDecimal capital, String signalMode,
                        Long usuarioId);

    void iniciarEstrategiaDetenida(Long instanciaId);

    void iniciarTodasDetenidas();

    /** Pausa una estrategia sin comprobar el dueño: uso interno (apagado). */
    void detenerEstrategia(Long instanciaId);

    /** Pausa todas las activas de todos los usuarios: uso interno (apagado). */
    void detenerTodas();

    void terminarEstrategia(Long instanciaId);

    void terminarTodas();

    // Variantes del usuario: lanzan ValidationException si la estrategia no existe o es de otro.

    void iniciarEstrategiaDetenida(Long instanciaId, Long usuarioId);

    void iniciarTodasDetenidas(Long usuarioId);

    void detenerEstrategia(Long instanciaId, Long usuarioId);

    void detenerTodas(Long usuarioId);

    void terminarEstrategia(Long instanciaId, Long usuarioId);

    void terminarTodas(Long usuarioId);

    /** Pasa a DETENIDA las ACTIVA sin runtime vivo (tras una caída o al arrancar). */
    void reconciliarEstadoConRuntime();

    /** Estrategias que ahora mismo tienen un proceso en tiempo real vivo (para el estado de salud). */
    java.util.Set<Long> idsConProceso();
}
