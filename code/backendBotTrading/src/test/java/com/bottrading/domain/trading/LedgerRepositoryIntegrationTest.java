package com.bottrading.domain.trading;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest(properties = "spring.main.allow-bean-definition-overriding=true")
@EntityScan(basePackageClasses = LedgerEntry.class)
@EnableJpaRepositories(basePackageClasses = LedgerRepository.class)
@ActiveProfiles("test")
class LedgerRepositoryIntegrationTest {

    @Autowired
    private LedgerRepository repository;

    @Test
    @DisplayName("Debe ordenar por timestamp DESC al consultar por wallet")
    void should_order_desc_by_timestamp_for_wallet() {
        repository.save(entry(1L, 10L, LedgerType.FEE, "-1", Instant.ofEpochMilli(1000L)));
        repository.save(entry(1L, 10L, LedgerType.REALIZED_PNL, "5", Instant.ofEpochMilli(3000L)));
        repository.save(entry(1L, 10L, LedgerType.AVAILABLE, "10", Instant.ofEpochMilli(2000L)));

        List<LedgerEntry> entries = repository.findByWalletIdOrderByTimestampDesc(1L);

        assertEquals(3, entries.size());
        assertEquals(3000L, entries.get(0).getTimestamp().toEpochMilli());
        assertEquals(2000L, entries.get(1).getTimestamp().toEpochMilli());
        assertEquals(1000L, entries.get(2).getTimestamp().toEpochMilli());
    }

    @Test
    @DisplayName("Debe filtrar por estrategia y ordenar DESC")
    void should_filter_by_estrategia_and_order_desc() {
        repository.save(entry(1L, 10L, LedgerType.FEE, "-1", Instant.ofEpochMilli(1000L)));
        repository.save(entry(1L, 20L, LedgerType.FEE, "-2", Instant.ofEpochMilli(5000L)));
        repository.save(entry(1L, 10L, LedgerType.REALIZED_PNL, "7", Instant.ofEpochMilli(3000L)));

        List<LedgerEntry> entries = repository.findByEstrategiaIdOrderByTimestampDesc(10L);

        assertEquals(2, entries.size());
        assertEquals(3000L, entries.get(0).getTimestamp().toEpochMilli());
        assertEquals(1000L, entries.get(1).getTimestamp().toEpochMilli());
    }

    private LedgerEntry entry(Long walletId, Long estrategiaId, LedgerType type, String amount, Instant ts) {
        LedgerEntry e = new LedgerEntry();
        e.setWalletId(walletId);
        e.setEstrategiaId(estrategiaId);
        e.setType(type);
        e.setAmount(new BigDecimal(amount));
        e.setBalanceAfter(new BigDecimal("1000"));
        e.setTimestamp(ts);
        return e;
    }
}
