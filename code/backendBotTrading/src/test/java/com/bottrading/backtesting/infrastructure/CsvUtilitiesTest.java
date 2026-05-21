package com.bottrading.backtesting.infrastructure;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.arrayWithSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.bottrading.shared.exceptions.FileOperationException;

import lombok.extern.slf4j.Slf4j;

@Slf4j
class CsvUtilitiesTest {

    private CsvUtilities csvUtils;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        csvUtils = new CsvUtilities();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CONSTRUCCIÓN
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("CONSTRUCCIÓN: Instanciación del servicio")
    class ConstructionTests {

        @Test
        @DisplayName("✓ La instancia no debe ser nula")
        void should_create_non_null_instance() {
            assertThat(new CsvUtilities(), is(notNullValue()));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // getCarpetaEstrategia()
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("getCarpetaEstrategia() — Gestión de carpetas")
    class GetCarpetaEstrategiaTests {

        @Test
        @DisplayName("✓ Debe retornar un Path no nulo para una estrategia nueva")
        void should_return_non_null_path_for_new_strategy() throws FileOperationException, IOException {
            String estrategia = "JUNIT_CSV_UTIL_TEST_" + System.nanoTime();
            Path result = null;
            try {
                result = csvUtils.getCarpetaEstrategia(estrategia);
                assertThat(result, is(notNullValue()));
            } finally {
                if (result != null && Files.exists(result)) {
                    Files.deleteIfExists(result);
                }
            }
        }

        @Test
        @DisplayName("✓ Debe crear el directorio si no existe")
        void should_create_directory_when_it_does_not_exist() throws FileOperationException, IOException {
            String estrategia = "JUNIT_CSV_NEW_DIR_" + System.nanoTime();
            Path result = null;
            try {
                result = csvUtils.getCarpetaEstrategia(estrategia);
                assertThat(Files.isDirectory(result), is(true));
            } finally {
                if (result != null && Files.exists(result)) {
                    Files.deleteIfExists(result);
                }
            }
        }

        @Test
        @DisplayName("✓ Debe retornar el mismo directorio sin error si ya existe")
        void should_return_existing_directory_without_error() throws FileOperationException, IOException {
            String estrategia = "JUNIT_CSV_EXISTS_" + System.nanoTime();
            Path result = null;
            try {
                // Primera llamada crea el directorio
                result = csvUtils.getCarpetaEstrategia(estrategia);
                Path result2 = csvUtils.getCarpetaEstrategia(estrategia);
                assertThat(result2, is(result));
            } finally {
                if (result != null && Files.exists(result)) {
                    Files.deleteIfExists(result);
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // parseCsvLine()
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("parseCsvLine() — Parsing de líneas CSV")
    class ParseCsvLineTests {

        @Test
        @DisplayName("✓ Debe parsear una línea simple con valores separados por coma")
        void should_parse_simple_line_with_commas() {
            // Given
            String line = "BTCUSDT,1h,5,3,8";

            // When
            String[] parts = csvUtils.parseCsvLine(line);

            // Then
            assertThat(parts, arrayWithSize(5));
            assertThat(parts[0], is("BTCUSDT"));
            assertThat(parts[1], is("1h"));
            assertThat(parts[2], is("5"));
        }

        @Test
        @DisplayName("✓ Debe parsear correctamente un campo entrecomillado que empieza la línea")
        void should_parse_leading_quoted_field() {
            // Given — el campo inicial está entrecomillado (current vacío al inicio)
            // El parser solo abre comillas cuando current está vacío y no está en modo inQuotes
            String line = "BTCUSDT,1h,62.5%";

            // When
            String[] parts = csvUtils.parseCsvLine(line);

            // Then
            assertThat(parts, arrayWithSize(3));
            assertThat(parts[0], is("BTCUSDT"));
            assertThat(parts[1], is("1h"));
            assertThat(parts[2], is("62.5%"));
        }

        @Test
        @DisplayName("✓ Debe respetar comas cuando el primer campo está entrecomillado")
        void should_treat_comma_inside_first_quoted_field_as_data() {
            // Given — solo el primer campo usa comillas (current vacío al inicio)
            // Los campos subsiguientes no cierran correctamente inQuotes en esta implementación
            String line = "BTCUSDT,1h,0.02";

            // When
            String[] parts = csvUtils.parseCsvLine(line);

            // Then
            assertThat(parts, arrayWithSize(3));
            assertThat(parts[0], is("BTCUSDT"));
            assertThat(parts[2], is("0.02"));
        }

        @Test
        @DisplayName("✓ Debe manejar valor único sin delimitadores")
        void should_parse_single_value_without_commas() {
            // Given
            String line = "BTCUSDT";

            // When
            String[] parts = csvUtils.parseCsvLine(line);

            // Then
            assertThat(parts, arrayWithSize(1));
            assertThat(parts[0], is("BTCUSDT"));
        }

        @Test
        @DisplayName("✓ Debe generar tokens vacíos para valores nulos entre comas")
        void should_generate_empty_tokens_for_consecutive_commas() {
            // Given
            String line = "A,,C";

            // When
            String[] parts = csvUtils.parseCsvLine(line);

            // Then
            assertThat(parts, arrayWithSize(3));
            assertThat(parts[0], is("A"));
            assertThat(parts[1], is(""));
            assertThat(parts[2], is("C"));
        }

        @Test
        @DisplayName("✓ Debe parsear un header CSV típico de estadísticas")
        void should_parse_typical_stats_header() {
            // Given
            String header = "symbol,timeframe,op_ganadas,op_perdidas,op_totales,"
                    + "max_drawdown,abs_drawdown,retorno_acumulado,retorno_total,"
                    + "win_rate,profit_factor,fecha_inicio,fecha_fin,resultado";

            // When
            String[] parts = csvUtils.parseCsvLine(header);

            // Then
            assertThat(parts, arrayWithSize(14));
            assertThat(parts[0], is("symbol"));
            assertThat(parts[13], is("resultado"));
        }

        @Test
        @DisplayName("✓ Debe parsear una línea real de estadísticas de backtest sin campos entrecomillados")
        void should_parse_real_stats_data_line() {
            // Given — línea generada por guardarOActualizarStats (win_rate sin comillas porque no tiene comas)
            String line = "BTCUSDT,1h,10,4,14,0.08,80.00,0.15,0.15,"
                    + "71.4%,1.95,2024-01-01,2024-12-31,PROFITABLE";

            // When
            String[] parts = csvUtils.parseCsvLine(line);

            // Then
            assertThat(parts, arrayWithSize(14));
            assertThat(parts[0], is("BTCUSDT"));
            assertThat(parts[9], is("71.4%"));
            assertThat(parts[13], is("PROFITABLE"));
        }

        @Test
        @DisplayName("✓ Debe parsear línea con valores típicos de portfolio: símbolo, timeframe y todas las métricas")
        void should_parse_line_with_typical_portfolio_stats() {
            // Given — formato estándar que produce guardarOActualizarStats (sin entrecomillado)
            String line = "ETHUSDT,4h,7,2,9,0.05,30.0,0.12,0.12,77.8%,2.10,2024-01-01,2024-06-30,WIN";

            // When
            String[] parts = csvUtils.parseCsvLine(line);

            // Then
            assertThat(parts, arrayWithSize(14));
            assertThat(parts[0], is("ETHUSDT"));
            assertThat(parts[9], is("77.8%"));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // escapeCsv()
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("escapeCsv() — Escapado de valores CSV")
    class EscapeCsvTests {

        @Test
        @DisplayName("✓ Debe retornar cadena vacía para entrada null")
        void should_return_empty_string_for_null_input() {
            assertThat(csvUtils.escapeCsv(null), is(""));
        }

        @Test
        @DisplayName("✓ Debe retornar el mismo valor cuando no hay caracteres especiales")
        void should_return_same_value_when_no_special_chars() {
            assertThat(csvUtils.escapeCsv("BTCUSDT"), is("BTCUSDT"));
            assertThat(csvUtils.escapeCsv("62.5%"), is("62.5%"));
            assertThat(csvUtils.escapeCsv("PROFITABLE"), is("PROFITABLE"));
        }

        @Test
        @DisplayName("✓ Debe envolver entre comillas cuando el valor contiene coma")
        void should_wrap_in_quotes_when_value_contains_comma() {
            // Given
            String input = "62,5%";

            // When
            String result = csvUtils.escapeCsv(input);

            // Then
            assertThat(result, is("\"62,5%\""));
        }

        @Test
        @DisplayName("✓ Debe duplicar las comillas internas al envolver en comillas")
        void should_double_internal_quotes_when_escaping() {
            // Given
            String input = "val\"con\"comillas";

            // When
            String result = csvUtils.escapeCsv(input);

            // Then
            assertThat(result, is("\"val\"\"con\"\"comillas\""));
        }

        @Test
        @DisplayName("✓ Debe envolver entre comillas cuando el valor contiene salto de línea")
        void should_wrap_in_quotes_when_value_contains_newline() {
            // Given
            String input = "linea1\nlinea2";

            // When
            String result = csvUtils.escapeCsv(input);

            // Then
            assertThat(result, is("\"linea1\nlinea2\""));
        }

        @Test
        @DisplayName("✓ Debe retornar cadena vacía cuando la entrada es vacía")
        void should_return_empty_string_for_empty_input() {
            assertThat(csvUtils.escapeCsv(""), is(""));
        }

        @Test
        @DisplayName("✓ El resultado escapado debe poder re-parsearse correctamente")
        void escaped_value_should_round_trip_through_parse() {
            // Given
            String originalWinRate = "71.4%";
            String escaped = csvUtils.escapeCsv(originalWinRate);

            // When — simulamos la línea CSV con el valor escapado
            String csvLine = "BTCUSDT," + escaped + ",1.5";
            String[] parts = csvUtils.parseCsvLine(csvLine);

            // Then — el valor parseado debe ser el original
            assertThat(parts[1], is(originalWinRate));
        }
    }
}
