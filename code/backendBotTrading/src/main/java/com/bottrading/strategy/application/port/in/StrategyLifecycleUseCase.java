package com.bottrading.strategy.application.port.in;

import java.math.BigDecimal;
import java.util.List;

/**
 * Puerto de entrada para gestión del ciclo de vida de estrategias.
 */
public interface StrategyLifecycleUseCase {

    void iniciarTradeRT(String nombreEstra, String nombreModelo, String tf, List<String> coins,
                        boolean isReal, Long walletId, BigDecimal risk, BigDecimal capital);

    void iniciarEstrategiaDetenida(Long instanciaId);

    void iniciarTodasDetenidas();

    void detenerEstrategia(Long instanciaId);

    void detenerTodas();

    void terminarEstrategia(Long instanciaId);

    void terminarTodas();
}
