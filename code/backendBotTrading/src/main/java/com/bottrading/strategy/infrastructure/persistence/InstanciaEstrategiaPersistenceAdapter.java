package com.bottrading.strategy.infrastructure.persistence;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.bottrading.strategy.application.port.out.InstanciaEstrategiaRepositoryPort;
import com.bottrading.strategy.domain.EstadoEstrategia;
import com.bottrading.strategy.domain.InstanciaEstrategia;
import com.bottrading.strategy.domain.InstanciaEstrategiaRepository;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class InstanciaEstrategiaPersistenceAdapter implements InstanciaEstrategiaRepositoryPort {

    private final InstanciaEstrategiaRepository instanciaEstrategiaRepository;

    @Override
    public List<InstanciaEstrategia> findAll() {
        return instanciaEstrategiaRepository.findAll();
    }

    @Override
    public List<InstanciaEstrategia> findByEstado(EstadoEstrategia estado) {
        return instanciaEstrategiaRepository.findByEstado(estado);
    }

    @Override
    public BigDecimal sumCapitalActivoByWallet(Long walletAsociada) {
        return instanciaEstrategiaRepository.sumCapitalActivoByWallet(walletAsociada);
    }

    @Override
    public Optional<InstanciaEstrategia> findById(Long id) {
        return instanciaEstrategiaRepository.findById(id);
    }

    @Override
    public Optional<InstanciaEstrategia> findByIdWithLock(Long id) {
        return instanciaEstrategiaRepository.findByIdWithLock(id);
    }

    @Override
    public <S extends InstanciaEstrategia> S save(S instancia) {
        return instanciaEstrategiaRepository.save(instancia);
    }
}
