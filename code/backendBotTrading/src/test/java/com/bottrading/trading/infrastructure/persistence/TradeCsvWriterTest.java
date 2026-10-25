package com.bottrading.trading.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mockStatic;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import com.bottrading.shared.utils.PathConfig;

class TradeCsvWriterTest {

    @TempDir
    Path tempDir;

    private Locale original;

    @BeforeEach
    void setLocale() {
        original = Locale.getDefault();
    }

    @AfterEach
    void restoreLocale() {
        Locale.setDefault(original);
    }

    @Test
    @DisplayName("El PnL se escribe con punto decimal aunque la JVM esté en es_ES (#71)")
    void pnl_uses_dot_decimal_separator_whatever_the_default_locale() throws IOException {
        Locale.setDefault(Locale.forLanguageTag("es-ES"));

        try (MockedStatic<PathConfig> path = mockStatic(PathConfig.class)) {
            path.when(() -> PathConfig.getCarpetaEstrategia("S")).thenReturn(tempDir);

            new TradeCsvWriter().guardarTrade("S", "1h", "BTCUSDT", "SELL",
                    new BigDecimal("0.0000001"), 1234L, new BigDecimal("1.5"));
        }

        List<String> lines = Files.readAllLines(tempDir.resolve("BTCUSDT-1h-trades.csv"));
        String row = lines.get(lines.size() - 1);
        assertEquals("BTCUSDT,1h,SELL,0.0000001,1234,1.50000000,", row);
        assertEquals(7, row.split(",", -1).length); // sin columna fantasma por la coma decimal
    }
}
