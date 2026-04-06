package com.bottrading.strategy.application.port.in;

import java.util.List;

/**
 * Puerto de entrada para consultas de estado de estrategias.
 */
public interface QueryStrategiesUseCase {

    List<String> listarEstrategias();

    List<String> listarEstrategiasActivas();

    List<String> listarEstrategiasDetenidas();

    List<String> listarEstrategiasTerminadas();
}
