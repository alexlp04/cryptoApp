package com.bottrading.market.application.port.in;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Puerto de entrada para operaciones de descarga y actualización de datos de mercado.
 */
public interface FetchMarketDataUseCase {

    void fetch(String symbol, String interval);

    void fullRefresh(String symbol, String interval, Integer days);

    void fillGapRange(String symbol, String interval, long fromTimestamp, long toTimestamp);

    int fetchRange(String symbol, String timeframe, LocalDateTime from, LocalDateTime to);

    void actualizarDatosMercado(List<String> symbols, String interval);

    void calcularIndicadoresParaSimbolo(String symbol, String interval);

    void prepararDatosParaEntrenamiento(String symbol, String interval, int dias, long now);

    LocalDateTime findOldestTimestamp(String symbol, String interval);
}
