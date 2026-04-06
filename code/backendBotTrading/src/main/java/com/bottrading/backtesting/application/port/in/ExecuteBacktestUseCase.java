package com.bottrading.backtesting.application.port.in;

import java.math.BigDecimal;
import java.util.List;

/**
 * Puerto de entrada para ejecución de backtests.
 */
public interface ExecuteBacktestUseCase {

    void ejecutarBacktest(String nombreEstra, String tf, List<String> coins,
                          BigDecimal capitalAsignado, BigDecimal risk,
                          boolean limpiarBacktestsPrevios, boolean guardarTrades);
}
