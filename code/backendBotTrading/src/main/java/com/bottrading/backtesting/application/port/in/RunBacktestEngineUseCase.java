package com.bottrading.backtesting.application.port.in;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import com.bottrading.market.domain.Vela;

/**
 * Puerto de entrada para el motor de backtesting Python.
 */
public interface RunBacktestEngineUseCase {

    String ejecutarBacktest(String rutaEstrategia, String nombreEstrategia, String timeframe,
                            Map<String, List<Vela>> velasPorSimbolo, BigDecimal capitalAsignado,
                            BigDecimal risk, boolean guardarTrades);
}
