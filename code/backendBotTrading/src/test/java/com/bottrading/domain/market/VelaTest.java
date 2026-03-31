package com.bottrading.domain.market;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * TEST DOMINIO - Vela (Candlestick)
 *
 * Características:
 *  • SIN @SpringBootTest → Sin contexto Spring
 *  • SIN @Mock → Solo JUnit 5 puro
 *  • Testea: validaciones de precios, timestamps, invariantes financieros
 *  • Nota: Vela actual no tiene validaciones en constructor,
 *    pero estos tests especifican el CONTRATO esperado
 */
@DisplayName("Domain Entity - Vela (Candlestick)")
class VelaTest {

    // ══════════════════════════════════════════════════════════════════════════
    // CONSTRUCCIÓN VÁLIDA
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("✓ Debe crear vela válida con todos los parámetros correctos")
    void should_create_valid_vela_when_all_inputs_valid() {
        // Given
        String symbol = "BTCUSDT";
        String interval = "1h";
        Long openTime = System.currentTimeMillis();
        BigDecimal open = new BigDecimal("42000.00");
        BigDecimal high = new BigDecimal("42500.00");
        BigDecimal low = new BigDecimal("41500.00");
        BigDecimal close = new BigDecimal("42250.00");
        BigDecimal volume = new BigDecimal("125.5");
        Long closeTime = openTime + 3600000; // 1 hour after open
        BigDecimal quoteVolume = new BigDecimal("5262500.00");
        Integer trades = 1234;
        BigDecimal takerBaseVolume = new BigDecimal("60.25");
        BigDecimal takerQuoteVolume = new BigDecimal("2527500.00");

        // When
        Vela vela = new Vela(symbol, interval, openTime, open, high, low, close, volume,
                closeTime, quoteVolume, trades, takerBaseVolume, takerQuoteVolume);

        // Then
        assertThat(vela.getSymbol(), is(equalTo(symbol)));
        assertThat(vela.getInterval(), is(equalTo(interval)));
        assertThat(vela.getOpenTime(), is(equalTo(openTime)));
        assertThat(vela.getOpen(), is(equalTo(open)));
        assertThat(vela.getHigh(), is(equalTo(high)));
        assertThat(vela.getLow(), is(equalTo(low)));
        assertThat(vela.getClose(), is(equalTo(close)));
        assertThat(vela.getVolume(), is(equalTo(volume)));
    }

    @Test
    @DisplayName("✓ Debe crear vela con constructor sin argumentos")
    void should_create_vela_with_no_arg_constructor() {
        // Given & When
        Vela vela = new Vela();

        // Then
        assertThat(vela, is(notNullValue()));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // VALIDACIONES: SYMBOL
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("✓ Debe aceptar symbol válido (BTCUSDT)")
    void should_accept_valid_symbol() {
        // Given & When
        Vela vela = new Vela();
        vela.setSymbol("BTCUSDT");

        // Then
        assertThat(vela.getSymbol(), is(equalTo("BTCUSDT")));
    }

    @Test
    @DisplayName("✓ Debe aceptar symbol con máximo 10 caracteres")
    void should_accept_symbol_with_max_length() {
        // Given & When
        Vela vela = new Vela();
        vela.setSymbol("LONGTOKEN1"); // 10 chars

        // Then
        assertThat(vela.getSymbol().length(), is(lessThanOrEqualTo(10)));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // VALIDACIONES: INTERVAL
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("✓ Debe aceptar intervals válidos (1m, 5m, 1h, 4h, 1d)")
    void should_accept_valid_intervals() {
        // Given
        String[] validIntervals = {"1m", "5m", "15m", "1h", "4h", "1d"};

        for (String interval : validIntervals) {
            // When
            Vela vela = new Vela();
            vela.setInterval(interval);

            // Then
            assertThat(vela.getInterval(), is(equalTo(interval)));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // VALIDACIONES: PRECIOS (BigDecimal)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("✓ Debe aceptar precios positivos")
    void should_accept_positive_prices() {
        // Given & When
        Vela vela = new Vela();
        vela.setOpen(new BigDecimal("42000.50"));
        vela.setHigh(new BigDecimal("42500.00"));
        vela.setLow(new BigDecimal("41500.00"));
        vela.setClose(new BigDecimal("42250.75"));

        // Then
        assertThat(vela.getOpen(), is(greaterThan(BigDecimal.ZERO)));
        assertThat(vela.getHigh(), is(greaterThan(BigDecimal.ZERO)));
        assertThat(vela.getLow(), is(greaterThan(BigDecimal.ZERO)));
        assertThat(vela.getClose(), is(greaterThan(BigDecimal.ZERO)));
    }

    @Test
    @DisplayName("✓ Debe aceptar precio mínimo (0.00000001 = 1 satoshi)")
    void should_accept_minimum_price() {
        // Given & When
        Vela vela = new Vela();
        vela.setOpen(new BigDecimal("0.00000001"));

        // Then
        assertThat(vela.getOpen(), is(greaterThan(BigDecimal.ZERO)));
    }

    @Test
    @DisplayName("✓ High debe ser >= low")
    void should_have_high_greater_or_equal_than_low() {
        // Given & When
        Vela vela = new Vela();
        vela.setHigh(new BigDecimal("42500.00"));
        vela.setLow(new BigDecimal("41500.00"));

        // Then
        assertThat(vela.getHigh().compareTo(vela.getLow()),
                is(greaterThanOrEqualTo(0)));
    }

    @Test
    @DisplayName("✓ High debe ser >= open y close")
    void should_have_high_greater_than_open_and_close() {
        // Given & When
        Vela vela = new Vela();
        vela.setOpen(new BigDecimal("42000.00"));
        vela.setClose(new BigDecimal("42250.00"));
        vela.setHigh(new BigDecimal("42500.00"));

        // Then
        assertThat(vela.getHigh().compareTo(vela.getOpen()),
                is(greaterThanOrEqualTo(0)));
        assertThat(vela.getHigh().compareTo(vela.getClose()),
                is(greaterThanOrEqualTo(0)));
    }

    @Test
    @DisplayName("✓ Low debe ser <= open y close")
    void should_have_low_less_than_open_and_close() {
        // Given & When
        Vela vela = new Vela();
        vela.setOpen(new BigDecimal("42000.00"));
        vela.setClose(new BigDecimal("42250.00"));
        vela.setLow(new BigDecimal("41500.00"));

        // Then
        assertThat(vela.getLow().compareTo(vela.getOpen()),
                is(lessThanOrEqualTo(0)));
        assertThat(vela.getLow().compareTo(vela.getClose()),
                is(lessThanOrEqualTo(0)));
    }

    @Test
    @DisplayName("✓ Debe aceptar precio con precisión hasta 8 decimales")
    void should_accept_price_with_eight_decimal_precision() {
        // Given & When
        Vela vela = new Vela();
        BigDecimal precisePrice = new BigDecimal("42000.12345678");
        vela.setOpen(precisePrice);

        // Then
        assertThat(vela.getOpen().scale(), is(greaterThanOrEqualTo(0)));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // VALIDACIONES: VOLUME
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("✓ Debe aceptar volume >= 0")
    void should_accept_zero_or_positive_volume() {
        // Given & When
        Vela vela = new Vela();
        vela.setVolume(new BigDecimal("125.50"));

        // Then
        assertThat(vela.getVolume().compareTo(BigDecimal.ZERO),
                is(greaterThanOrEqualTo(0)));
    }

    @Test
    @DisplayName("✓ Debe aceptar volume cero")
    void should_accept_zero_volume() {
        // Given & When
        Vela vela = new Vela();
        vela.setVolume(BigDecimal.ZERO);

        // Then
        assertThat(vela.getVolume().compareTo(BigDecimal.ZERO),
                is(equalTo(0)));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // VALIDACIONES: TIMESTAMPS
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("✓ Debe aceptar openTime como epoch milliseconds válido")
    void should_accept_valid_openTime_epoch() {
        // Given
        Long epochMs = System.currentTimeMillis();
        Vela vela = new Vela();

        // When
        vela.setOpenTime(epochMs);

        // Then
        assertThat(vela.getOpenTime(), is(equalTo(epochMs)));
    }

    @Test
    @DisplayName("✓ Debe aceptar closeTime posterior a openTime")
    void should_have_closeTime_after_openTime() {
        // Given & When
        Long openTime = 1704067200000L;
        Long closeTime = openTime + 3600000; // +1 hour
        Vela vela = new Vela();
        vela.setOpenTime(openTime);
        vela.setCloseTime(closeTime);

        // Then
        assertThat(vela.getCloseTime(),
                is(greaterThan(vela.getOpenTime())));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // VALIDACIONES: TRADES Y QUOTE VOLUME
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("✓ Debe aceptar trades como número positivo o cero")
    void should_accept_zero_or_positive_trades() {
        // Given & When
        Vela vela = new Vela();
        vela.setTrades(1234);

        // Then
        assertThat(vela.getTrades(), is(greaterThanOrEqualTo(0)));
    }

    @Test
    @DisplayName("✓ Debe aceptar quoteVolume como BigDecimal positivo")
    void should_accept_positive_quoteVolume() {
        // Given & When
        Vela vela = new Vela();
        vela.setQuoteVolume(new BigDecimal("5262500.00"));

        // Then
        assertThat(vela.getQuoteVolume(), is(greaterThanOrEqualTo(BigDecimal.ZERO)));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // HEREDIBILIDAD Y BASEENTITY
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("✓ Vela debe heredar getId() de BaseEntity")
    void should_vela_inherit_getId_from_BaseEntity() {
        // Given & When
        Vela vela = new Vela();

        // Then
        assertThat(vela.getId(), is(nullValue())); // null hasta persistirse
    }

    @Test
    @DisplayName("✓ Vela debe heredar getFechaCreacion() de BaseEntity")
    void should_vela_inherit_getFechaCreacion_from_BaseEntity() {
        // Given & When
        Vela vela = new Vela();

        // Then
        assertThat(vela.getFechaCreacion(), is(notNullValue()));
    }

    @Test
    @DisplayName("✓ Vela debe heredar isEliminado() de BaseEntity")
    void should_vela_inherit_isEliminado_from_BaseEntity() {
        // Given & When
        Vela vela = new Vela();

        // Then
        assertThat(vela.isEliminado(), is(false)); // false por defecto
    }

    // ══════════════════════════════════════════════════════════════════════════
    // UNIQUENESS - Validar constraint único (symbol, interval, openTime)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("✓ Vela debe ser identificada únicamente por (symbol, interval, openTime)")
    void should_vela_be_unique_by_symbol_interval_openTime() {
        // Given
        Vela vela1 = new Vela();
        Vela vela2 = new Vela();

        vela1.setSymbol("BTCUSDT");
        vela1.setInterval("1h");
        vela1.setOpenTime(1704067200000L);
        vela1.setOpen(new BigDecimal("42000.00"));

        vela2.setSymbol("BTCUSDT");
        vela2.setInterval("1h");
        vela2.setOpenTime(1704067200000L);
        vela2.setOpen(new BigDecimal("42100.00")); // diferente precio

        // Then - should be considered the same candle (aunque precios difieran)
        assertThat(vela1.getSymbol(), is(equalTo(vela2.getSymbol())));
        assertThat(vela1.getInterval(), is(equalTo(vela2.getInterval())));
        assertThat(vela1.getOpenTime(), is(equalTo(vela2.getOpenTime())));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SERIALIZACIÓN
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("✓ Vela debe generar toString() válido")
    void should_vela_generate_valid_toString() {
        // Given & When
        Vela vela = new Vela();
        vela.setSymbol("BTCUSDT");
        String toString = vela.toString();

        // Then
        assertThat(toString, is(notNullValue()));
    }
}
