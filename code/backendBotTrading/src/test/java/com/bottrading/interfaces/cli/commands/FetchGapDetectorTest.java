package com.bottrading.interfaces.cli.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.bottrading.market.domain.VelaRepository;

class FetchGapDetectorTest {

    private static final long H = 3_600_000L;

    private final VelaRepository repo = mock(VelaRepository.class);
    private final FetchGapDetector detector = new FetchGapDetector(repo);

    private static LocalDateTime utc(long ms) {
        return LocalDateTime.ofEpochSecond(ms / 1000, 0, ZoneOffset.UTC);
    }

    @Test
    @DisplayName("La vela en curso no es un hueco: última cerrada guardada y ahora a mitad de la siguiente (#110)")
    void in_progress_candle_is_not_a_gap() {
        long base = 1_700_000_000_000L - (1_700_000_000_000L % H);
        long last = base;                       // abre a las X:00, cerró a las X+1:00
        long now = base + H + H / 2;            // X+1:30 -> la vela de X+1:00 aún se está formando
        when(repo.findOpenTimesBySymbolAndIntervalBetweenOrderByOpenTimeAsc(anyString(), anyString(), anyLong(), anyLong()))
                .thenReturn(List.of(base - 2 * H, base - H, last));

        var gaps = detector.detectGaps("BTCUSDT", "1h", utc(base - 2 * H), utc(now));

        assertTrue(gaps.isEmpty(), "no debe informar un hueco final falso: " + gaps);
    }

    @Test
    @DisplayName("Sí informa como hueco las velas ya cerradas que faltan al final")
    void closed_missing_candles_at_the_end_are_a_gap() {
        long base = 1_700_000_000_000L - (1_700_000_000_000L % H);
        long now = base + 5 * H + H / 2;        // cerradas: base+1h ... base+4h
        when(repo.findOpenTimesBySymbolAndIntervalBetweenOrderByOpenTimeAsc(anyString(), anyString(), anyLong(), anyLong()))
                .thenReturn(List.of(base));

        var gaps = detector.detectGaps("BTCUSDT", "1h", utc(base), utc(now));

        assertEquals(1, gaps.size());
        assertEquals(utc(base + H), gaps.getFirst().left());
        assertEquals(utc(base + 4 * H), gaps.getFirst().right());
    }

    @Test
    @DisplayName("Un timeframe no soportado o absurdo (0m) se rechaza (#110 #84)")
    void unsupported_timeframes_are_rejected() {
        when(repo.findOpenTimesBySymbolAndIntervalBetweenOrderByOpenTimeAsc(anyString(), anyString(), anyLong(), anyLong()))
                .thenReturn(List.of());

        assertThrows(IllegalArgumentException.class,
                () -> detector.detectGaps("BTCUSDT", "0m", utc(0), utc(H)));
        assertThrows(IllegalArgumentException.class,
                () -> detector.detectGaps("BTCUSDT", "2h", utc(0), utc(H)));
    }
}
