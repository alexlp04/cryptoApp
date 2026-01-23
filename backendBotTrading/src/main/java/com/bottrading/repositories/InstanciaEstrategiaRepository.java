package com.bottrading.repositories;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.bottrading.beans.InstanciaEstrategia;

import jakarta.persistence.LockModeType;

@Repository
public interface InstanciaEstrategiaRepository extends JpaRepository<InstanciaEstrategia, Long> {

    // Sustituye sumCapitalActivoByWallet
    @Query("SELECT SUM(i.capitalAsignado) FROM InstanciaEstrategia i " +
            "WHERE i.walletAsociada = :nombreWallet AND i.estado = 'ACTIVA'")
    Double sumCapitalActivoByWallet(String nombreWallet);

    // Sustituye findActivasByWallet
    List<InstanciaEstrategia> findByWalletAsociadaAndEstado(String nombreWallet, String estado);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM InstanciaEstrategia i WHERE i.id = :id")
    Optional<InstanciaEstrategia> findByIdWithLock(Long id);

}