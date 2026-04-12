package com.bottrading.strategy.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;

@Repository
public interface InstanciaEstrategiaRepository extends JpaRepository<InstanciaEstrategia, Long> {

    @Query("SELECT SUM(i.capitalAsignado) FROM InstanciaEstrategia i "
            + "WHERE i.walletAsociada = :walletAsociada "
            + "AND i.estado = com.bottrading.strategy.domain.EstadoEstrategia.ACTIVA")
    BigDecimal sumCapitalActivoByWallet(Long walletAsociada);

    List<InstanciaEstrategia> findByEstado(EstadoEstrategia estado);

    List<InstanciaEstrategia> findByWalletAsociadaAndEstado(Long walletAsociada, EstadoEstrategia estado);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM InstanciaEstrategia i WHERE i.id = :id")
    Optional<InstanciaEstrategia> findByIdWithLock(Long id);
}