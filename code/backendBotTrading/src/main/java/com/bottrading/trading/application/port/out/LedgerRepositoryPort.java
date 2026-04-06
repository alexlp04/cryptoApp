package com.bottrading.trading.application.port.out;

import com.bottrading.trading.domain.LedgerEntry;

import java.util.List;

/**
 * Puerto de salida para persistencia de entradas del ledger.
 */
public interface LedgerRepositoryPort {

    List<LedgerEntry> findByInstanciaEstrategiaId(Long instanciaId);

    <S extends LedgerEntry> S save(S entry);
}
