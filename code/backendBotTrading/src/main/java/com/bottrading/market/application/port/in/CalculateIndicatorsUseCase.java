package com.bottrading.market.application.port.in;

import com.bottrading.market.domain.Vela;

import java.util.List;

/**
 * Puerto de entrada para cálculo de indicadores técnicos.
 */
public interface CalculateIndicatorsUseCase {

    void calculateBasicIndicators(String symbol, List<Vela> todasLasVelas, boolean guardarPrimeras50);
}
