package com.bottrading.strategy.application.port.out;

import java.util.List;
import java.util.Optional;

import com.bottrading.strategy.domain.EstadoEstrategia;
import com.bottrading.strategy.domain.InstanciaEstrategia;

/**
 * Puerto de salida para persistencia de instancias de estrategia.
 */
public interface InstanciaEstrategiaRepositoryPort {

    List<InstanciaEstrategia> findByEstado(EstadoEstrategia estado);

    Optional<InstanciaEstrategia> findById(Long id);

    <S extends InstanciaEstrategia> S save(S instancia);
}
