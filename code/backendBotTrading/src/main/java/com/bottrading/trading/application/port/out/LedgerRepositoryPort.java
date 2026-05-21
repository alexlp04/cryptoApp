package com.bottrading.trading.application.port.out;

import java.util.List;

import com.bottrading.trading.domain.LedgerEntry;

/**
 * Puerto de salida para persistencia de entradas del ledger.
 */
public interface LedgerRepositoryPort {

    List<LedgerEntry> findByWalletIdOrderByTimestampDesc(Long walletId);

    List<LedgerEntry> findByEstrategiaIdOrderByTimestampDesc(Long estrategiaId);

    <S extends LedgerEntry> S save(S entry);
}
