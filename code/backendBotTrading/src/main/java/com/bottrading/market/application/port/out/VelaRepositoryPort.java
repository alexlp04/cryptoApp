package com.bottrading.market.application.port.out;

import com.bottrading.market.domain.Vela;

import java.util.List;
import java.util.Optional;

/**
 * Puerto de salida para persistencia de velas de mercado.
 */
public interface VelaRepositoryPort {

    List<Vela> findBySymbolAndIntervalOrderByOpenTimeAsc(String symbol, String interval);

    Optional<Vela> findTopBySymbolAndIntervalOrderByOpenTimeDesc(String symbol, String interval);

    <S extends Vela> S save(S vela);
}
