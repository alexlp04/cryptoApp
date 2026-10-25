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
                                boolean isReal, Long walletId, BigDecimal risk, BigDecimal capital) {
        iniciarTradeRT(nombreEstra, nombreModelo, tf, coins, isReal, walletId, risk, capital, "close");
    }

    /** signalMode: "close" (al cerrar la vela), "open" (al abrir la siguiente) o "both". */
    @SuppressWarnings("java:S107")
    void iniciarTradeRT(String nombreEstra, String nombreModelo, String tf, List<String> coins,
                        boolean isReal, Long walletId, BigDecimal risk, BigDecimal capital, String signalMode);

    void iniciarEstrategiaDetenida(Long instanciaId);

    void iniciarTodasDetenidas();

    void detenerEstrategia(Long instanciaId);

    void detenerTodas();

    void terminarEstrategia(Long instanciaId);

    void terminarTodas();

    /** Pasa a DETENIDA las ACTIVA sin runtime vivo (tras una caída o al arrancar). */
    void reconciliarEstadoConRuntime();
}
