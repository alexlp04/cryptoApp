package com.bottrading.market.application.port.out;

import com.bottrading.market.domain.IndicadorTecnico;

import java.util.List;

/**
 * Puerto de salida para persistencia de indicadores técnicos.
 */
public interface IndicadorRepositoryPort {

    List<IndicadorTecnico> findByVelaId(Long velaId);

    <S extends IndicadorTecnico> S save(S indicador);
}
