package com.bottrading.trading.infrastructure.persistence;

import java.math.BigDecimal;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.bottrading.backtesting.infrastructure.StatsCsvRepository;
import com.bottrading.trading.application.port.out.TradeResultsPort;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class TradeCsvAdapter implements TradeResultsPort {

    private final TradeCsvWriter tradeCsvWriter;
    private final StatsCsvRepository statsCsvRepository;

    @Override
    public void guardarTrade(String nombreEstrategia, String timeframe, String symbol,
            String side, BigDecimal price, long timestamp, BigDecimal pnl) {
        tradeCsvWriter.guardarTrade(nombreEstrategia, timeframe, symbol, side, price, timestamp, pnl);
    }

    @Override
    public void guardarStats(String nombreEstrategia, String timeframe, Map<String, Object> stats,
            boolean isBacktest) {
        statsCsvRepository.guardarStats(nombreEstrategia, timeframe, stats, isBacktest);
    }
}
