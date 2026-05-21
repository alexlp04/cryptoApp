package com.bottrading.market.application.port.in;

import java.time.LocalDateTime;

/**
 * Puerto de entrada para operaciones de descarga de datos de mercado.
 */
public interface FetchMarketDataUseCase {

    void fetch(String symbol, String interval);

    void fullRefresh(String symbol, String interval, Integer days);

    LocalDateTime findOldestTimestamp(String symbol, String interval);

    void fillGapRange(String symbol, String interval, long fromTimestamp, long toTimestamp);

    int fetchRange(String symbol, String timeframe, LocalDateTime from, LocalDateTime to);

    long fetchIncremental(String symbol, String interval, int dias, long now);
}
