package com.bottrading.trading.domain;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LedgerRepository extends JpaRepository<LedgerEntry, Long> {

    List<LedgerEntry> findByWalletIdOrderByTimestampDesc(Long walletId);

    List<LedgerEntry> findByEstrategiaIdOrderByTimestampDesc(Long estrategiaId);
}