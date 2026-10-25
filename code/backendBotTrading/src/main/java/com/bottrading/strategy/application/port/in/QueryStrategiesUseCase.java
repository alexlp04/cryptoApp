package com.bottrading.strategy.application.port.in;

import java.util.List;

/**
 * Puerto de entrada para consultas de estado de estrategias. Cada usuario ve solo las suyas.
 */
public interface QueryStrategiesUseCase {

    List<String> listarEstrategias(Long usuarioId);

    List<String> listarEstrategiasActivas(Long usuarioId);

    List<String> listarEstrategiasDetenidas(Long usuarioId);

    List<String> listarEstrategiasTerminadas(Long usuarioId);
}
