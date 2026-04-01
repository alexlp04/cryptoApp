package com.bottrading.interfaces.cli.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.StringReader;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import com.bottrading.application.market.MarketDataService;
import com.bottrading.interfaces.cli.CliCommandContext;

class FetchCommandTest {

    @Test
    void executeDownloadsUsingPositionalSyntaxAndDaysOption() {
        MarketDataService marketDataService = Mockito.mock(MarketDataService.class);
        FetchGapDetector fetchGapDetector = Mockito.mock(FetchGapDetector.class);

        List<String> printed = new ArrayList<>();
        CliCommandContext context = new CliCommandContext(
                new Scanner(new StringReader("")),
                null,
                null,
                null,
                null,
                null,
                marketDataService,
                fetchGapDetector,
                s -> {
                },
                printed::add);

        FetchCommand command = new FetchCommand();
        command.execute(new String[] { "fetch", "BTC/USDT", "1h", "-d", "30" }, context);

        verify(marketDataService).fullRefresh("BTC/USDT", "1h", 30);
        verify(fetchGapDetector, never()).detectGaps(any(), any(), any(), any());
        assertEquals("Full refresh para BTC/USDT [1h]...", printed.get(0));
        assertEquals("Sincronizacion completa para BTC/USDT.", printed.get(1));
    }

    @Test
    void executeDetectGapsModePrintsGapsAndSkipsDownload() {
        MarketDataService marketDataService = Mockito.mock(MarketDataService.class);
        FetchGapDetector fetchGapDetector = Mockito.mock(FetchGapDetector.class);

        LocalDateTime gapStart = LocalDateTime.of(2024, 1, 15, 3, 0);
        LocalDateTime gapEnd = LocalDateTime.of(2024, 1, 15, 7, 0);

        when(fetchGapDetector.detectGaps(eq("BTC/USDT"), eq("1h"), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.of(new Pair<>(gapStart, gapEnd)));

        List<String> printed = new ArrayList<>();
        CliCommandContext context = new CliCommandContext(
                new Scanner(new StringReader("")),
                null,
                null,
                null,
                null,
                null,
                marketDataService,
                fetchGapDetector,
                s -> {
                },
                printed::add);

        FetchCommand command = new FetchCommand();
        command.execute(new String[] { "fetch", "BTC/USDT", "1h", "--detect-gaps", "--days", "30" }, context);

        verify(marketDataService, never()).actualizarDatosMercado(any(), any());
        verify(marketDataService).fillGapRange("BTC/USDT", "1h", gapStart, gapEnd);
        verify(fetchGapDetector).detectGaps(eq("BTC/USDT"), eq("1h"), any(LocalDateTime.class), any(LocalDateTime.class));

        assertEquals("Gaps detected for BTC/USDT [1h]:", printed.get(0));
        assertEquals("[1] 2024-01-15 03:00 → 2024-01-15 07:00", printed.get(1));
        assertEquals("Total: 1 gap(s) found.", printed.get(2));
    }

    @Test
    void executeDetectGapsUsesDefaultDaysWhenDaysNotProvided() {
        MarketDataService marketDataService = Mockito.mock(MarketDataService.class);
        FetchGapDetector fetchGapDetector = Mockito.mock(FetchGapDetector.class);

        when(fetchGapDetector.detectGaps(eq("ETHUSDT"), eq("15m"), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.of());

        List<String> printed = new ArrayList<>();
        CliCommandContext context = new CliCommandContext(
                new Scanner(new StringReader("")),
                null,
                null,
                null,
                null,
                null,
                marketDataService,
                fetchGapDetector,
                s -> {
                },
                printed::add);

        FetchCommand command = new FetchCommand();
                LocalDateTime oldest = LocalDateTime.of(2025, 12, 1, 0, 0);
                when(marketDataService.findOldestTimestamp("ETHUSDT", "15m")).thenReturn(oldest);

                command.execute(new String[] { "fetch", "-c", "ETHUSDT", "-tf", "15m", "--detect-gaps" }, context);

        ArgumentCaptor<LocalDateTime> fromCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> toCaptor = ArgumentCaptor.forClass(LocalDateTime.class);

        verify(fetchGapDetector).detectGaps(eq("ETHUSDT"), eq("15m"), fromCaptor.capture(), toCaptor.capture());
                verify(marketDataService).findOldestTimestamp("ETHUSDT", "15m");

                assertEquals(oldest, fromCaptor.getValue());
        assertEquals("Gaps detected for ETHUSDT [15m]:", printed.get(0));
        assertEquals("Total: 0 gap(s) found.", printed.get(1));
    }

        @Test
        void executeDetectGapsWithoutDaysAndNoExistingDataPrintsMessage() {
                MarketDataService marketDataService = Mockito.mock(MarketDataService.class);
                FetchGapDetector fetchGapDetector = Mockito.mock(FetchGapDetector.class);

                when(marketDataService.findOldestTimestamp("BTCUSDT", "1m")).thenReturn(null);

                List<String> printed = new ArrayList<>();
                CliCommandContext context = new CliCommandContext(
                                new Scanner(new StringReader("")),
                                null,
                                null,
                                null,
                                null,
                                null,
                                marketDataService,
                                fetchGapDetector,
                                s -> {
                                },
                                printed::add);

                FetchCommand command = new FetchCommand();
                command.execute(new String[] { "fetch", "-c", "BTCUSDT", "-t", "1m", "--detect-gaps" }, context);

                verify(marketDataService).findOldestTimestamp("BTCUSDT", "1m");
                verify(fetchGapDetector, never()).detectGaps(any(), any(), any(), any());
                assertEquals("No hay datos existentes para BTCUSDT [1m] para detectar huecos.", printed.getFirst());
        }
}
