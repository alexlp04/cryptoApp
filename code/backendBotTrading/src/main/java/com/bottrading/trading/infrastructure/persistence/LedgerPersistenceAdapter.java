package com.bottrading.trading.infrastructure.persistence;

import java.util.List;

import org.springframework.stereotype.Component;

import com.bottrading.trading.application.port.out.LedgerRepositoryPort;
import com.bottrading.trading.domain.LedgerEntry;
import com.bottrading.trading.domain.LedgerRepository;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class LedgerPersistenceAdapter implements LedgerRepositoryPort {

    private final LedgerRepository ledgerRepository;

    @Override
    public List<LedgerEntry> findByWalletIdOrderByTimestampDesc(Long walletId) {
        return ledgerRepository.findByWalletIdOrderByTimestampDesc(walletId);
    }

    @Override
    public List<LedgerEntry> findByEstrategiaIdOrderByTimestampDesc(Long estrategiaId) {
        return ledgerRepository.findByEstrategiaIdOrderByTimestampDesc(estrategiaId);
    }

    @Override
    public <S extends LedgerEntry> S save(S entry) {
        return ledgerRepository.save(entry);
    }
}
