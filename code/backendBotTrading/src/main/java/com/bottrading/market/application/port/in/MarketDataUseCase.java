package com.bottrading.market.application.port.in;

import java.util.List;

/**
 * Puerto de entrada para orquestacion de datos de mercado.
 */
public interface MarketDataUseCase {

    void actualizarDatosMercado(List<String> symbols, String interval);

}
