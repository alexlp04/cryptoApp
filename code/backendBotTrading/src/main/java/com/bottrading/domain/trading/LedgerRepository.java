package com.bottrading.domain.trading;

import com.bottrading.domain.trading.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface LedgerRepository extends JpaRepository<LedgerEntry, Long> {
    // Para ver el historial de una wallet específica
    List<LedgerEntry> findByWalletIdOrderByTimestampDesc(Long walletId);

    // Para ver los movimientos de una estrategia concreta
    List<LedgerEntry> findByEstrategiaIdOrderByTimestampDesc(Long estrategiaId);
}