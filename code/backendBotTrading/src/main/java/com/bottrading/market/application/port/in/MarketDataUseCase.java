package com.bottrading.market.application.port.in;

import java.util.List;

/**
 * Puerto de entrada para orquestacion de datos de mercado.
 */
public interface MarketDataUseCase {

    void actualizarDatosMercado(List<String> symbols, String interval);

    void calcularIndicadoresParaSimbolo(String symbol, String interval);

    void prepararDatosParaEntrenamiento(String symbol, String interval, int dias, long now);
}
