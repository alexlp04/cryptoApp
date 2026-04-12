package com.bottrading.trading.application.port.out;

import java.util.Optional;

import com.bottrading.strategy.domain.InstanciaEstrategia;
import com.bottrading.trading.domain.Posicion;

/**
 * Puerto de salida para persistencia de posiciones de trading.
 */
public interface PosicionRepositoryPort {

    Optional<Posicion> findById(Long id);

    Optional<Posicion> findByInstanciaAndSimboloAndAbiertaTrue(InstanciaEstrategia instancia, String simbolo);

    boolean existsByInstanciaAndSimboloAndAbiertaTrue(InstanciaEstrategia instancia, String simbolo);

    <S extends Posicion> S save(S posicion);
}
