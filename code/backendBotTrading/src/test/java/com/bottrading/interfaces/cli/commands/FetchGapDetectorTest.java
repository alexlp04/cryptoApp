package com.bottrading.interfaces.cli.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.bottrading.domain.market.VelaRepository;

class FetchGapDetectorTest {

    @Test
    void detectGapsReturnsEmptyWhenNoMissingCandles() {
        VelaRepository velaRepository = Mockito.mock(VelaRepository.class);
        FetchGapDetector detector = new FetchGapDetector(velaRepository);

        LocalDateTime from = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 1, 1, 3, 0);

        long t0 = epochMs(from);
        long t1 = epochMs(from.plusHours(1));
        long t2 = epochMs(from.plusHours(2));

        Mockito.when(velaRepository.findOpenTimesBySymbolAndIntervalBetweenOrderByOpenTimeAsc(
                        "BTCUSDT", "1h", epochMs(from), epochMs(to)))
                .thenReturn(List.of(t0, t1, t2));

        List<Pair<LocalDateTime, LocalDateTime>> gaps = detector.detectGaps("BTCUSDT", "1h", from, to);

        assertTrue(gaps.isEmpty());
    }

    @Test
    void detectGapsFindsMissingRangeBetweenConsecutiveTimestamps() {
        VelaRepository velaRepository = Mockito.mock(VelaRepository.class);
        FetchGapDetector detector = new FetchGapDetector(velaRepository);

        LocalDateTime from = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 1, 1, 6, 0);

        long t0 = epochMs(from);
        long t3 = epochMs(from.plusHours(3));

        Mockito.when(velaRepository.findOpenTimesBySymbolAndIntervalBetweenOrderByOpenTimeAsc(
                        "BTCUSDT", "1h", epochMs(from), epochMs(to)))
                .thenReturn(List.of(t0, t3));

        List<Pair<LocalDateTime, LocalDateTime>> gaps = detector.detectGaps("BTCUSDT", "1h", from, to);

        assertEquals(1, gaps.size());
        assertEquals(from.plusHours(1), gaps.getFirst().left());
        assertEquals(from.plusHours(2), gaps.getFirst().right());
    }

    @Test
    void detectGapsHonorsOneSecondTolerance() {
        VelaRepository velaRepository = Mockito.mock(VelaRepository.class);
        FetchGapDetector detector = new FetchGapDetector(velaRepository);

        LocalDateTime from = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 1, 1, 2, 0);

        long t0 = epochMs(from);
        long t1WithTolerance = t0 + 3_600_000L + 1_000L;

        Mockito.when(velaRepository.findOpenTimesBySymbolAndIntervalBetweenOrderByOpenTimeAsc(
                        "ETHUSDT", "1h", epochMs(from), epochMs(to)))
                .thenReturn(List.of(t0, t1WithTolerance));

        List<Pair<LocalDateTime, LocalDateTime>> gaps = detector.detectGaps("ETHUSDT", "1h", from, to);

        assertTrue(gaps.isEmpty());
    }

    @Test
    void detectGapsThrowsForUnsupportedTimeframe() {
        VelaRepository velaRepository = Mockito.mock(VelaRepository.class);
        FetchGapDetector detector = new FetchGapDetector(velaRepository);

        LocalDateTime from = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 1, 1, 1, 0);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> detector.detectGaps("BTCUSDT", "foo", from, to));

        assertTrue(ex.getMessage().contains("Unsupported timeframe"));
    }

    private static long epochMs(LocalDateTime dateTime) {
        return dateTime.toInstant(ZoneOffset.UTC).toEpochMilli();
    }
}
