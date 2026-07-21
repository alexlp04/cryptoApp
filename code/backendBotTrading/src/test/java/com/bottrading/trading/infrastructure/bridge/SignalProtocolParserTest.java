package com.bottrading.trading.infrastructure.bridge;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.bottrading.shared.exceptions.SignalProcessingException;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SignalProtocolParserTest {

    @InjectMocks
    private SignalProtocolParser parser;

    // ─────────────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────────────
    private String buildSignalJson(String symbol, String action, double price) {
        return String.format(
                "{\"symbol\":\"%s\",\"action\":\"%s\",\"price\":%s,\"timeframe\":\"1h\",\"timestamp\":1234567890}",
                symbol, action, BigDecimal.valueOf(price).toPlainString());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CONSTRUCCIÓN
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("CONSTRUCCIÓN: Instanciación del parser")
    class ConstructionTests {

        @Test
        @DisplayName("✓ La instancia no debe ser nula")
        void should_create_non_null_instance() {
            assertThat(parser, is(notNullValue()));
        }

        @Test
        @DisplayName("✓ Debe aceptar instanciación directa sin dependencias")
        void should_instantiate_directly() {
            SignalProtocolParser p = new SignalProtocolParser();
            assertThat(p, is(notNullValue()));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // parseLineaLog() — líneas de log normales
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("parseLineaLog() — Líneas sin prefijo SIGNAL")
    class ParseLogLineTests {

        @Test
        @DisplayName("✓ Debe retornar null para línea de log normal sin prefijo SIGNAL")
        void should_return_null_for_plain_log_line() {
            SignalDTO result = parser.parseLineaLog("INFO: RSI calculado = 55.3", 1L);
            assertThat(result, is(nullValue()));
        }

        @Test
        @DisplayName("✓ Debe retornar null para línea vacía")
        void should_return_null_for_empty_line() {
            SignalDTO result = parser.parseLineaLog("", 1L);
            assertThat(result, is(nullValue()));
        }

        @Test
        @DisplayName("✓ Debe retornar null para línea de error Python")
        void should_return_null_for_python_error_line() {
            SignalDTO result = parser.parseLineaLog("ERROR: Traceback (most recent call last)", 2L);
            assertThat(result, is(nullValue()));
        }

        @Test
        @DisplayName("✓ Debe ser tolerante a líneas con solo espacios")
        void should_return_null_for_whitespace_only() {
            SignalDTO result = parser.parseLineaLog("   ", 1L);
            assertThat(result, is(nullValue()));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // parseLineaLog() — líneas con prefijo SIGNAL
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("parseLineaLog() — Líneas con prefijo SIGNAL")
    class ParseSignalLineTests {

        @Test
        @DisplayName("✓ Debe parsear señal BUY válida con todos los campos")
        void should_parse_valid_buy_signal() {
            String line = "SIGNAL\t" + buildSignalJson("BTCUSDT", "BUY", 42000.0);

            SignalDTO result = parser.parseLineaLog(line, 1L);

            assertThat(result, is(notNullValue()));
            assertThat(result.getSymbol(), is("BTCUSDT"));
            assertThat(result.getAction(), is("BUY"));
            assertThat(result.getPrice().compareTo(new BigDecimal("42000.0")), is(0));
        }

        @Test
        @DisplayName("✓ Debe parsear señal SELL válida")
        void should_parse_valid_sell_signal() {
            String line = "SIGNAL\t" + buildSignalJson("ETHUSDT", "SELL", 3000.5);

            SignalDTO result = parser.parseLineaLog(line, 2L);

            assertThat(result, is(notNullValue()));
            assertThat(result.getSymbol(), is("ETHUSDT"));
            assertThat(result.getAction(), is("SELL"));
        }

        @Test
        @DisplayName("✓ Debe funcionar con espacios alrededor de la línea (trim)")
        void should_handle_leading_trailing_spaces() {
            String line = "  SIGNAL\t" + buildSignalJson("BTCUSDT", "BUY", 40000.0) + "  ";

            SignalDTO result = parser.parseLineaLog(line, 1L);

            assertThat(result, is(notNullValue()));
            assertThat(result.getAction(), is("BUY"));
        }

        @Test
        @DisplayName("✓ Debe retornar null para JSON malformado tras prefijo SIGNAL")
        void should_return_null_for_malformed_json() {
            String line = "SIGNAL\t{ invalid json !!!";

            SignalDTO result = parser.parseLineaLog(line, 1L);

            assertThat(result, is(nullValue()));
        }

        @Test
        @DisplayName("✓ Debe retornar null para JSON vacío tras prefijo SIGNAL")
        void should_return_null_for_empty_json() {
            String line = "SIGNAL\t";

            SignalDTO result = parser.parseLineaLog(line, 1L);

            assertThat(result, is(nullValue()));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // parseLineaLog() / isHeartbeat() — líneas de heartbeat (liveness)
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("Heartbeat — líneas de liveness")
    class HeartbeatTests {

        @Test
        @DisplayName("✓ Debe reconocer una línea HEARTBEAT con payload JSON")
        void should_detect_heartbeat_with_payload() {
            assertThat(parser.isHeartbeat("HEARTBEAT\t{\"type\":\"heartbeat\"}"), is(true));
        }

        @Test
        @DisplayName("✓ Debe reconocer HEARTBEAT con espacios alrededor")
        void should_detect_heartbeat_with_spaces() {
            assertThat(parser.isHeartbeat("  HEARTBEAT\t{}  "), is(true));
        }

        @Test
        @DisplayName("✓ Un heartbeat no debe producir señal (retorna null)")
        void should_return_null_for_heartbeat() {
            SignalDTO result = parser.parseLineaLog("HEARTBEAT\t{\"type\":\"heartbeat\"}", 1L);
            assertThat(result, is(nullValue()));
        }

        @Test
        @DisplayName("✓ Una línea de log normal no debe considerarse heartbeat")
        void should_not_detect_plain_log_as_heartbeat() {
            assertThat(parser.isHeartbeat("INFO: conectando a Binance"), is(false));
        }

        @Test
        @DisplayName("✓ isHeartbeat debe ser tolerante a null")
        void should_handle_null_in_is_heartbeat() {
            assertThat(parser.isHeartbeat(null), is(false));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // validarSignal (via parseLineaLog) — campo symbol
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("validarSignal() — Validación de campos obligatorios")
    class ValidarSignalTests {

        @Test
        @DisplayName("✓ Debe lanzar SignalProcessingException cuando falta el symbol")
        void should_throw_when_symbol_is_missing() {
            String line = "SIGNAL\t{\"action\":\"BUY\",\"price\":40000,\"timeframe\":\"1h\",\"timestamp\":1234}";

            assertThrows(SignalProcessingException.class,
                    () -> parser.parseLineaLog(line, 1L));
        }

        @Test
        @DisplayName("✓ Debe lanzar SignalProcessingException cuando symbol está vacío")
        void should_throw_when_symbol_is_empty() {
            String line = "SIGNAL\t{\"symbol\":\"\",\"action\":\"BUY\",\"price\":40000,\"timestamp\":1234}";

            assertThrows(SignalProcessingException.class,
                    () -> parser.parseLineaLog(line, 1L));
        }

        @Test
        @DisplayName("✓ Debe lanzar SignalProcessingException cuando falta la action")
        void should_throw_when_action_is_missing() {
            String line = "SIGNAL\t{\"symbol\":\"BTCUSDT\",\"price\":40000,\"timeframe\":\"1h\",\"timestamp\":1234}";

            assertThrows(SignalProcessingException.class,
                    () -> parser.parseLineaLog(line, 1L));
        }

        @Test
        @DisplayName("✓ Debe lanzar SignalProcessingException cuando action está vacía")
        void should_throw_when_action_is_empty() {
            String line = "SIGNAL\t{\"symbol\":\"BTCUSDT\",\"action\":\"\",\"price\":40000,\"timestamp\":1234}";

            assertThrows(SignalProcessingException.class,
                    () -> parser.parseLineaLog(line, 1L));
        }

        @Test
        @DisplayName("✓ Debe aceptar señal con solo symbol y action como mínimo")
        void should_accept_signal_with_minimum_required_fields() {
            String line = "SIGNAL\t{\"symbol\":\"BTCUSDT\",\"action\":\"BUY\"}";

            SignalDTO result = parser.parseLineaLog(line, 1L);

            assertThat(result, is(notNullValue()));
            assertThat(result.getSymbol(), is("BTCUSDT"));
            assertThat(result.getAction(), is("BUY"));
        }
    }
}
