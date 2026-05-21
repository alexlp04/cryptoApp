package com.bottrading.market.application.port.out;

import java.util.List;
import java.util.Optional;

import com.bottrading.market.domain.IndicadorTecnico;
import com.bottrading.market.domain.Vela;

/**
 * Puerto de salida para persistencia de indicadores técnicos.
 */
public interface IndicadorRepositoryPort {

    List<IndicadorTecnico> findByVela(Vela vela);

    List<IndicadorTecnico> findByVelaAndTipo(Vela vela, String tipo);

    Optional<IndicadorTecnico> findByVelaAndTipoAndParametros(Vela vela, String tipo, String parametros);

    boolean existsByVelaAndTipoAndParametros(Vela vela, String tipo, String parametros);

    boolean existsByVelaId(Long velaId);

    void deleteByVela(Vela vela);

    void deleteByVelaSymbolAndIntervalAndOpenTimeGreaterThanEqual(String symbol, String interval, Long openTime);

    List<IndicadorTecnico> findByVelaIn(List<Vela> velas);

    <S extends IndicadorTecnico> S save(S indicador);
}
