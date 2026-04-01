package com.bottrading.domain.strategy;

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

    // Sustituye sumCapitalActivoByWallet
    @Query("SELECT SUM(i.capitalAsignado) FROM InstanciaEstrategia i " +
            "WHERE i.walletAsociada = :walletAsociada AND i.estado = 'ACTIVA'")
        BigDecimal sumCapitalActivoByWallet(Long walletAsociada);

    List<InstanciaEstrategia> findByEstado(String estado);

    // Sustituye findActivasByWallet
    List<InstanciaEstrategia> findByWalletAsociadaAndEstado(Long walletAsociada, String estado);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM InstanciaEstrategia i WHERE i.id = :id")
    Optional<InstanciaEstrategia> findByIdWithLock(Long id);

}