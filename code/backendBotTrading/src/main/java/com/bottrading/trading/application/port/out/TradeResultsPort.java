package com.bottrading.trading.application.port.out;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Puerto de salida para persistencia de resultados de paper trading.
 */
public interface TradeResultsPort {

    void guardarTrade(String nombreEstrategia, String timeframe, String symbol,
            String side, BigDecimal price, long timestamp, BigDecimal pnl);

    void guardarStats(String nombreEstrategia, String timeframe, Map<String, Object> stats, boolean isBacktest);
}
