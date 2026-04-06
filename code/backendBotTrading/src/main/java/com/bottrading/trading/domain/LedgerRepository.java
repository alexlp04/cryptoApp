package com.bottrading.trading.domain;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LedgerRepository extends JpaRepository<LedgerEntry, Long> {
    // Para ver el historial de una wallet específica
    List<LedgerEntry> findByWalletIdOrderByTimestampDesc(Long walletId);

    // Para ver los movimientos de una estrategia concreta
    List<LedgerEntry> findByEstrategiaIdOrderByTimestampDesc(Long estrategiaId);
}