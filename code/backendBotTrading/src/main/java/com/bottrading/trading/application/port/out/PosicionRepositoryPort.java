package com.bottrading.trading.application.port.out;

import com.bottrading.trading.domain.Posicion;

import java.util.Optional;

/**
 * Puerto de salida para persistencia de posiciones de trading.
 */
public interface PosicionRepositoryPort {

    Optional<Posicion> findById(Long id);

    <S extends Posicion> S save(S posicion);
}
