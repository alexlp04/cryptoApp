package com.bottrading.trading.domain;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.bottrading.strategy.domain.InstanciaEstrategia;

@Repository
public interface PosicionRepository extends JpaRepository<Posicion, Long> {

    Optional<Posicion> findByInstanciaAndSimboloAndAbiertaTrue(InstanciaEstrategia instancia, String simbolo);

    boolean existsByInstanciaAndSimboloAndAbiertaTrue(InstanciaEstrategia instancia, String simbolo);
}