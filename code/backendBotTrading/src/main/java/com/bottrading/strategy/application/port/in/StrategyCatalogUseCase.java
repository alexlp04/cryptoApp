package com.bottrading.strategy.application.port.in;

import java.math.BigDecimal;
import java.util.List;

/**
 * Puerto de entrada para catálogo de estrategias disponibles.
 */
public interface StrategyCatalogUseCase {

    List<String> listarFicherosDeEstrategias();

    BigDecimal getCapitalComprometido(Long walletAsociada);

    BigDecimal getCapitalDisponible(Long walletAsociada, BigDecimal saldoTotal);

    boolean existeEstrategia(String nombreArchivo);

    String getValidStrategyPath(String nombreEstra);
}
