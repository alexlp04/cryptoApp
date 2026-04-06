package com.bottrading.interfaces.cli.commands;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.bottrading.market.domain.VelaRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Detects missing candle ranges for a symbol/timeframe in a date interval.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FetchGapDetector {

    private static final long TOLERANCE_MS = 1_000L;

    private final VelaRepository velaRepository;

    public List<Pair<LocalDateTime, LocalDateTime>> detectGaps(
            String symbol,
            String timeframe,
            LocalDateTime fromDate,
            LocalDateTime toDate) {

        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol cannot be null or blank");
        }
        if (timeframe == null || timeframe.isBlank()) {
            throw new IllegalArgumentException("timeframe cannot be null or blank");
        }
        if (fromDate == null || toDate == null) {
            throw new IllegalArgumentException("fromDate/toDate cannot be null");
        }
        if (toDate.isBefore(fromDate)) {
            return List.of();
        }

        long expectedIntervalMs = resolveTimeframeMillis(timeframe);
        long fromEpochMs = fromDate.toInstant(ZoneOffset.UTC).toEpochMilli();
        long toEpochMs = toDate.toInstant(ZoneOffset.UTC).toEpochMilli();

        List<Long> timestamps = velaRepository.findOpenTimesBySymbolAndIntervalBetweenOrderByOpenTimeAsc(
                symbol, timeframe, fromEpochMs, toEpochMs);

        if (timestamps.size() < 2) {
            return List.of();
        }

        List<Pair<LocalDateTime, LocalDateTime>> gaps = new ArrayList<>();

        for (int i = 1; i < timestamps.size(); i++) {
            long previous = timestamps.get(i - 1);
            long current = timestamps.get(i);
            long diff = current - previous;

            if (diff > expectedIntervalMs + TOLERANCE_MS) {
                long gapStartMs = previous + expectedIntervalMs;
                long gapEndMs = current - expectedIntervalMs;

                if (gapStartMs <= gapEndMs) {
                    LocalDateTime gapStart = LocalDateTime.ofEpochSecond(
                            gapStartMs / 1000L,
                            (int) ((gapStartMs % 1000L) * 1_000_000),
                            ZoneOffset.UTC);
                    LocalDateTime gapEnd = LocalDateTime.ofEpochSecond(
                            gapEndMs / 1000L,
                            (int) ((gapEndMs % 1000L) * 1_000_000),
                            ZoneOffset.UTC);

                    gaps.add(new Pair<>(gapStart, gapEnd));
                    log.warn("Gap detected symbol={} timeframe={} start={} end={}",
                            symbol, timeframe, gapStart, gapEnd);
                }
            }
        }

        return gaps;
    }

    private long resolveTimeframeMillis(String timeframe) {
        if (timeframe.length() < 2) {
            throw new IllegalArgumentException("Unsupported timeframe: " + timeframe);
        }

        String numericPart = timeframe.substring(0, timeframe.length() - 1);
        char unit = timeframe.charAt(timeframe.length() - 1);

        int value;
        try {
            value = Integer.parseInt(numericPart);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Unsupported timeframe: " + timeframe, ex);
        }

        return switch (unit) {
            case 's' -> value * 1_000L;
            case 'm' -> value * 60_000L;
            case 'h' -> value * 3_600_000L;
            case 'd' -> value * 86_400_000L;
            default -> throw new IllegalArgumentException("Unsupported timeframe: " + timeframe);
        };
    }
}
