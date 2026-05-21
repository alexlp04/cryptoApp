package com.bottrading.trading.infrastructure.persistence;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.bottrading.strategy.domain.InstanciaEstrategia;
import com.bottrading.trading.application.port.out.PosicionRepositoryPort;
import com.bottrading.trading.domain.Posicion;
import com.bottrading.trading.domain.PosicionRepository;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class PosicionPersistenceAdapter implements PosicionRepositoryPort {

    private final PosicionRepository posicionRepository;

    @Override
    public Optional<Posicion> findById(Long id) {
        return posicionRepository.findById(id);
    }

    @Override
    public Optional<Posicion> findByInstanciaAndSimboloAndAbiertaTrue(InstanciaEstrategia instancia, String simbolo) {
        return posicionRepository.findByInstanciaAndSimboloAndAbiertaTrue(instancia, simbolo);
    }

    @Override
    public boolean existsByInstanciaAndSimboloAndAbiertaTrue(InstanciaEstrategia instancia, String simbolo) {
        return posicionRepository.existsByInstanciaAndSimboloAndAbiertaTrue(instancia, simbolo);
    }

    @Override
    public <S extends Posicion> S save(S posicion) {
        return posicionRepository.save(posicion);
    }
}
