package com.bottrading.domain.market;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@EntityScan(basePackageClasses = Vela.class)
@EnableJpaRepositories(basePackageClasses = VelaRepository.class)
@ActiveProfiles("test")
class VelaRepositoryIntegrationTest {

    @Autowired
    private VelaRepository velaRepository;

    @Test
    @DisplayName("Debe persistir y recuperar velas ordenadas por openTime")
    void should_save_and_find_ordered_by_open_time() {
        velaRepository.save(vela("BTCUSDT", "1h", 2000L));
        velaRepository.save(vela("BTCUSDT", "1h", 1000L));
        velaRepository.save(vela("BTCUSDT", "1h", 3000L));

        List<Vela> result = velaRepository.findBySymbolAndIntervalOrderByOpenTimeAsc("BTCUSDT", "1h");

        assertEquals(3, result.size());
        assertEquals(1000L, result.get(0).getOpenTime());
        assertEquals(2000L, result.get(1).getOpenTime());
        assertEquals(3000L, result.get(2).getOpenTime());
    }

    @Test
    @DisplayName("Debe devolver último timestamp por símbolo e intervalo")
    void should_find_max_open_time() {
        velaRepository.save(vela("BTCUSDT", "1h", 1000L));
        velaRepository.save(vela("BTCUSDT", "1h", 5000L));
        velaRepository.save(vela("BTCUSDT", "1h", 3000L));

        Long max = velaRepository.findMaxOpenTimeBySymbolAndInterval("BTCUSDT", "1h");

        assertEquals(5000L, max);
    }

    @Test
    @DisplayName("Debe devolver mínimo timestamp por símbolo e intervalo")
    void should_find_min_open_time() {
        velaRepository.save(vela("BTCUSDT", "1h", 1000L));
        velaRepository.save(vela("BTCUSDT", "1h", 5000L));
        velaRepository.save(vela("BTCUSDT", "1h", 3000L));

        Long min = velaRepository.findMinOpenTimeBySymbolAndInterval("BTCUSDT", "1h");

        assertEquals(1000L, min);
    }

    @Test
    @DisplayName("Debe detectar existencia por symbol-interval-openTime")
    void should_check_existence_by_unique_triplet() {
        velaRepository.save(vela("ETHUSDT", "4h", 7000L));

        assertTrue(velaRepository.existsBySymbolAndIntervalAndOpenTime("ETHUSDT", "4h", 7000L));
        assertFalse(velaRepository.existsBySymbolAndIntervalAndOpenTime("ETHUSDT", "4h", 7001L));
    }

    @Test
    @DisplayName("Debe borrar velas desde openTime inclusive")
    void should_delete_from_open_time_inclusive() {
        velaRepository.save(vela("BTCUSDT", "1h", 1000L));
        velaRepository.save(vela("BTCUSDT", "1h", 2000L));
        velaRepository.save(vela("BTCUSDT", "1h", 3000L));

        velaRepository.deleteBySymbolAndIntervalAndOpenTimeGreaterThanEqual("BTCUSDT", "1h", 2000L);

        List<Vela> remaining = velaRepository.findBySymbolAndIntervalOrderByOpenTimeAsc("BTCUSDT", "1h");
        assertEquals(1, remaining.size());
        assertEquals(1000L, remaining.get(0).getOpenTime());
    }

    @Test
    @DisplayName("Debe recuperar velas desde openTime inclusive ordenadas")
    void should_find_from_open_time_inclusive_ordered() {
        velaRepository.save(vela("BTCUSDT", "1h", 1000L));
        velaRepository.save(vela("BTCUSDT", "1h", 2000L));
        velaRepository.save(vela("BTCUSDT", "1h", 3000L));

        List<Vela> result = velaRepository
            .findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc("BTCUSDT", "1h", 2000L);

        assertEquals(2, result.size());
        assertEquals(2000L, result.get(0).getOpenTime());
        assertEquals(3000L, result.get(1).getOpenTime());
    }

    @Test
    @DisplayName("Debe contar velas dentro de un rango")
    void should_count_velas_in_range() {
        velaRepository.save(vela("BTCUSDT", "1h", 1000L));
        velaRepository.save(vela("BTCUSDT", "1h", 2000L));
        velaRepository.save(vela("BTCUSDT", "1h", 3000L));
        velaRepository.save(vela("BTCUSDT", "1h", 4000L));

        long count = velaRepository.countBySymbolAndIntervalAndOpenTimeBetween("BTCUSDT", "1h", 1500L, 3500L);

        assertEquals(2L, count);
    }

    @Test
    @DisplayName("Debe devolver first internal gap openTime")
    void should_find_first_internal_gap_open_time() {
        // Candles: 1000, 2000, 4000 -> gap interno esperado en 3000 con step=1000
        velaRepository.save(vela("BTCUSDT", "1h", 1000L));
        velaRepository.save(vela("BTCUSDT", "1h", 2000L));
        velaRepository.save(vela("BTCUSDT", "1h", 4000L));

        Long gap = velaRepository.findFirstInternalGapOpenTime("BTCUSDT", "1h", 1000L, 4000L, 1000L);

        assertNotNull(gap);
        assertEquals(3000L, gap);
    }

    private Vela vela(String symbol, String interval, Long openTime) {
        Vela v = new Vela();
        v.setSymbol(symbol);
        v.setInterval(interval);
        v.setOpenTime(openTime);
        v.setOpen(new BigDecimal("40000.00"));
        v.setHigh(new BigDecimal("40500.00"));
        v.setLow(new BigDecimal("39500.00"));
        v.setClose(new BigDecimal("40200.00"));
        v.setVolume(new BigDecimal("1200.00"));
        v.setCloseTime(openTime + 3599000L);
        v.setQuoteVolume(new BigDecimal("48000000.00"));
        v.setTrades(100);
        v.setTakerBaseVolume(new BigDecimal("500.00"));
        v.setTakerQuoteVolume(new BigDecimal("20000000.00"));
        return v;
    }
}
