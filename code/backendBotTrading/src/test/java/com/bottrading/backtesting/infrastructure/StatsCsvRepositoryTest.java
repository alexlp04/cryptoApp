package com.bottrading.backtesting.infrastructure;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.bottrading.shared.exceptions.FileOperationException;
import com.bottrading.shared.utils.AppConstants;
import com.bottrading.shared.utils.PathConfig;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StatsCsvRepositoryTest {

    @Spy
    private CsvUtilities csvUtils;

    @InjectMocks
    private StatsCsvRepository statsCsvRepository;

    @TempDir
    Path tempDir;

    // Estrategia de test para leerStatsActuales (usa PathConfig.RESULTS_DIR directamente)
    private static final String LEER_TEST_STRATEGY = "JUNIT_STATS_LEER_TEST";
    private static final String MODEL_NAME = "lightgbm";
    private Path leerTestDir;

    // ─── JSON de ejemplo para guardarEstadisticasDelBacktest ─────────────────
    private static final String JSON_CON_STATS = "{"
            + "\"stats\":[{"
            + "\"symbol\":\"BTCUSDT\","
            + "\"timeframe\":\"1h\","
            + "\"op_ganadas\":\"10\","
            + "\"op_perdidas\":\"4\","
            + "\"op_totales\":\"14\","
            + "\"max_drawdown\":\"0.08\","
            + "\"abs_drawdown\":\"80.00\","
            + "\"retorno_acumulado\":\"0.15\","
            + "\"retorno_total\":\"0.15\","
            + "\"win_rate\":\"71.4%\","
            + "\"profit_factor\":\"1.95\","
            + "\"fecha_inicio\":\"2024-01-01\","
            + "\"fecha_fin\":\"2024-12-31\","
            + "\"resultado\":\"PROFITABLE\""
            + "}]}";

    @BeforeEach
    void setUp() throws Exception {
        // Todos los métodos que usan getCarpetaEstrategia van al tempDir
        doReturn(tempDir).when(csvUtils).getCarpetaEstrategia(anyString());
    }

    @AfterEach
    void tearDown() throws IOException {
        // Limpia la carpeta temporal del test de leerStatsActuales
        if (leerTestDir != null && Files.exists(leerTestDir)) {
            try (var stream = Files.walk(leerTestDir)) {
                stream.sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try {
                                Files.delete(p);
                            } catch (IOException e) {
                                log.warn("tearDown: no se pudo borrar {}", p);
                            }
                        });
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CONSTRUCCIÓN
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("CONSTRUCCIÓN: Instanciación del repositorio")
    class ConstructionTests {

        @Test
        @DisplayName("✓ La instancia no debe ser nula")
        void should_create_non_null_instance() {
            assertThat(new StatsCsvRepository(new CsvUtilities()), is(notNullValue()));
        }

        @Test
        @DisplayName("✓ Debe aceptar una CsvUtilities por constructor")
        void should_accept_csv_utilities_in_constructor() {
            StatsCsvRepository sut = new StatsCsvRepository(csvUtils);
            assertThat(sut, is(notNullValue()));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // guardarEstadisticasDelBacktest()
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("guardarEstadisticasDelBacktest() — Guardado desde JSON Python")
    class GuardarEstadisticasDelBacktestTests {

        @Test
        @DisplayName("✓ Debe crear un CSV con timeframe, modelo y moneda al guardar estadísticas válidas")
        void should_create_model_named_backtest_csv_for_valid_json() {
            // When
            statsCsvRepository.guardarEstadisticasDelBacktest("RSISMAStrategy", "1h", MODEL_NAME, JSON_CON_STATS);

            // Then
            Path csvFile = buildModelBacktestPath("1h", MODEL_NAME, "BTCUSDT");
            assertThat(Files.exists(csvFile), is(true));
        }

        @Test
        @DisplayName("✓ Debe escribir el header en la primera línea del CSV")
        void should_write_header_as_first_line() throws IOException {
            // When
            statsCsvRepository.guardarEstadisticasDelBacktest("RSISMAStrategy", "1h", MODEL_NAME, JSON_CON_STATS);

            // Then
            Path csvFile = buildModelBacktestPath("1h", MODEL_NAME, "BTCUSDT");
            String firstLine = Files.readAllLines(csvFile, StandardCharsets.UTF_8).get(0);
            assertThat(firstLine, is(AppConstants.CSV_HEADER_STATS));
        }

        @Test
        @DisplayName("✓ Debe escribir el símbolo correcto en la línea de datos")
        void should_write_correct_symbol_in_data_line() throws IOException {
            // When
            statsCsvRepository.guardarEstadisticasDelBacktest("RSISMAStrategy", "1h", MODEL_NAME, JSON_CON_STATS);

            // Then
            Path csvFile = buildModelBacktestPath("1h", MODEL_NAME, "BTCUSDT");
            String dataLine = Files.readAllLines(csvFile, StandardCharsets.UTF_8).get(1);
            assertThat(dataLine.startsWith("BTCUSDT"), is(true));
        }

        @Test
        @DisplayName("✓ Debe lanzar FileOperationException para JSON inválido")
        void should_throw_fileOperationException_for_invalid_json() {
            assertThrows(FileOperationException.class, () ->
                    statsCsvRepository.guardarEstadisticasDelBacktest(
                    "RSISMAStrategy", "1h", MODEL_NAME, "esto no es json {{{"));
        }

        @Test
        @DisplayName("✓ No debe lanzar excepción cuando stats es null en el JSON")
        void should_not_throw_when_stats_key_is_absent_in_json() {
            // Given — JSON sin clave "stats"
            String jsonSinStats = "{\"otro_campo\":\"valor\"}";

            // When / Then — no debe lanzar
            statsCsvRepository.guardarEstadisticasDelBacktest("RSISMAStrategy", "1h", MODEL_NAME, jsonSinStats);
        }

        @Test
        @DisplayName("✓ Debe mantener el nombre legacy cuando el modelo no viene informado")
        void should_keep_legacy_backtest_filename_when_model_name_is_blank() {
            // When
            statsCsvRepository.guardarEstadisticasDelBacktest("RSISMAStrategy", "1h", " ", JSON_CON_STATS);

            // Then
            Path csvFile = tempDir.resolve("results_backtest.csv");
            assertThat(Files.exists(csvFile), is(true));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // guardarStats()
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("guardarStats() — Actualización incremental del CSV de estadísticas")
    class GuardarStatsTests {

        @Test
        @DisplayName("✓ Debe crear el CSV con header cuando aún no existe")
        void should_create_csv_with_header_when_file_does_not_exist() throws IOException {
            // When
            statsCsvRepository.guardarStats("RSISMAStrategy", "1h", crearStatsTest("BTCUSDT"), true);

            // Then
            Path csvFile = tempDir.resolve("results_backtest.csv");
            assertThat(Files.exists(csvFile), is(true));
            String firstLine = Files.readAllLines(csvFile, StandardCharsets.UTF_8).get(0);
            assertThat(firstLine, is(AppConstants.CSV_HEADER_STATS));
        }

        @Test
        @DisplayName("✓ Debe añadir exactamente una fila de datos tras el header")
        void should_append_one_data_row_after_header() throws IOException {
            // When
            statsCsvRepository.guardarStats("RSISMAStrategy", "1h", crearStatsTest("BTCUSDT"), true);

            // Then
            Path csvFile = tempDir.resolve("results_backtest.csv");
            assertThat(Files.readAllLines(csvFile).size(), is(2)); // header + 1 dato
        }

        @Test
        @DisplayName("✓ Debe sobrescribir fila existente para mismo símbolo y timeframe")
        void should_overwrite_existing_row_for_same_symbol_and_timeframe() throws IOException {
            // Given — primera escritura
            statsCsvRepository.guardarStats("RSISMAStrategy", "1h", crearStatsTest("BTCUSDT"), true);

            // When — segunda escritura con mismos símbolo + timeframe
            Map<String, Object> statsActualizadas = crearStatsTest("BTCUSDT");
            statsActualizadas.put(AppConstants.KEY_OP_GANADAS, "99");
            statsCsvRepository.guardarStats("RSISMAStrategy", "1h", statsActualizadas, true);

            // Then — debe haber solo 2 líneas (header + 1 fila actualizada)
            Path csvFile = tempDir.resolve("results_backtest.csv");
            assertThat(Files.readAllLines(csvFile).size(), is(2));
        }

        @Test
        @DisplayName("✓ Debe añadir nueva fila para símbolo diferente sin perder las previas")
        void should_append_new_row_for_different_symbol() throws IOException {
            // Given — primera moneda
            statsCsvRepository.guardarStats("RSISMAStrategy", "1h", crearStatsTest("BTCUSDT"), true);

            // When — segunda moneda distinta
            statsCsvRepository.guardarStats("RSISMAStrategy", "1h", crearStatsTest("ETHUSDT"), true);

            // Then — header + 2 filas de datos
            Path csvFile = tempDir.resolve("results_backtest.csv");
            assertThat(Files.readAllLines(csvFile).size(), is(3));
        }

        @Test
        @DisplayName("✓ Debe crear results.csv (sin sufijo _backtest) cuando isBacktest es false")
        void should_create_results_csv_when_isBacktest_is_false() throws IOException {
            // When
            statsCsvRepository.guardarStats("RSISMAStrategy", "1h", crearStatsTest("BTCUSDT"), false);

            // Then
            Path csvFile = tempDir.resolve("results.csv");
            assertThat(Files.exists(csvFile), is(true));
        }

        @Test
        @DisplayName("✓ Debe crear un CSV de backtest por modelo cuando se informa modelName")
        void should_create_model_named_backtest_csv_when_model_name_is_present() throws IOException {
            // When
            statsCsvRepository.guardarStats("RSISMAStrategy", "1h", crearStatsTest("BTCUSDT"), true, MODEL_NAME);

            // Then
            Path csvFile = buildModelBacktestPath("1h", MODEL_NAME, "BTCUSDT");
            assertThat(Files.exists(csvFile), is(true));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // leerStatsActuales()
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("leerStatsActuales() — Lectura de estadísticas persistidas")
    class LeerStatsActualesTests {

        @Test
        @DisplayName("✓ Debe retornar mapa vacío cuando el archivo no existe")
        void should_return_empty_map_when_file_does_not_exist() {
            // Given — estrategia que seguro no tiene carpeta results
            String estrategiaSinDatos = "ESTRATEGIA_INEXISTENTE_JTEST_" + System.nanoTime();

            // When
            Map<String, Object> result =
                    statsCsvRepository.leerStatsActuales(estrategiaSinDatos, "1h", "BTCUSDT");

            // Then
            assertThat(result.isEmpty(), is(true));
        }

        @Test
        @DisplayName("✓ Debe retornar mapa vacío cuando el archivo existe pero no hay coincidencia")
        void should_return_empty_map_when_file_exists_but_no_match() throws IOException {
            // Given — creamos un results.csv bajo PathConfig.RESULTS_DIR con datos de ETHUSDT
            leerTestDir = Paths.get(PathConfig.RESULTS_DIR, LEER_TEST_STRATEGY);
            Files.createDirectories(leerTestDir);
            Path csvFile = leerTestDir.resolve("results.csv");
            Files.writeString(csvFile,
                    AppConstants.CSV_HEADER_STATS + System.lineSeparator()
                            + buildStatsCsvRow("ETHUSDT", "1h"),
                    StandardCharsets.UTF_8);

            // When — buscamos BTCUSDT que no está en el archivo
            Map<String, Object> result =
                    statsCsvRepository.leerStatsActuales(LEER_TEST_STRATEGY, "1h", "BTCUSDT");

            // Then
            assertThat(result.isEmpty(), is(true));
        }

        @Test
        @DisplayName("✓ Debe retornar las estadísticas correctas cuando hay coincidencia")
        void should_return_matching_stats_when_symbol_and_timeframe_match() throws IOException {
            // Given — creamos un results.csv bajo PathConfig.RESULTS_DIR con datos de BTCUSDT
            leerTestDir = Paths.get(PathConfig.RESULTS_DIR, LEER_TEST_STRATEGY);
            Files.createDirectories(leerTestDir);
            Path csvFile = leerTestDir.resolve("results.csv");
            Files.writeString(csvFile,
                    AppConstants.CSV_HEADER_STATS + System.lineSeparator()
                            + buildStatsCsvRow("BTCUSDT", "1h"),
                    StandardCharsets.UTF_8);

            // When
            Map<String, Object> result =
                    statsCsvRepository.leerStatsActuales(LEER_TEST_STRATEGY, "1h", "BTCUSDT");

            // Then
            assertThat(result.isEmpty(), is(false));
            assertThat(result, hasKey(AppConstants.KEY_SYMBOL));
            assertThat(result.get(AppConstants.KEY_SYMBOL), is("BTCUSDT"));
        }

        @Test
        @DisplayName("✓ Debe retornar mapa vacío cuando el timeframe no coincide")
        void should_return_empty_map_when_timeframe_does_not_match() throws IOException {
            // Given
            leerTestDir = Paths.get(PathConfig.RESULTS_DIR, LEER_TEST_STRATEGY);
            Files.createDirectories(leerTestDir);
            Path csvFile = leerTestDir.resolve("results.csv");
            Files.writeString(csvFile,
                    AppConstants.CSV_HEADER_STATS + System.lineSeparator()
                            + buildStatsCsvRow("BTCUSDT", "4h"),
                    StandardCharsets.UTF_8);

            // When — buscamos con timeframe "1h" pero en el CSV está "4h"
            Map<String, Object> result =
                    statsCsvRepository.leerStatsActuales(LEER_TEST_STRATEGY, "1h", "BTCUSDT");

            // Then
            assertThat(result.isEmpty(), is(true));
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────
    private Map<String, Object> crearStatsTest(String symbol) {
        Map<String, Object> stats = new HashMap<>();
        stats.put(AppConstants.KEY_SYMBOL, symbol);
        stats.put(AppConstants.KEY_TIMEFRAME, "1h");
        stats.put(AppConstants.KEY_OP_GANADAS, "10");
        stats.put(AppConstants.KEY_OP_PERDIDAS, "4");
        stats.put(AppConstants.KEY_OP_TOTALES, "14");
        stats.put(AppConstants.KEY_MAX_DRAWDOWN, "0.08");
        stats.put(AppConstants.KEY_ABS_DRAWDOWN, "80.00");
        stats.put(AppConstants.KEY_RET_ACUMULADO, "0.15");
        stats.put(AppConstants.KEY_RET_TOTAL, "0.15");
        stats.put(AppConstants.KEY_WIN_RATE, "71.4%");
        stats.put(AppConstants.KEY_PROFIT_FACTOR, "1.95");
        stats.put(AppConstants.KEY_FECHA_INICIO, "2024-01-01");
        stats.put(AppConstants.KEY_FECHA_FIN, "2024-12-31");
        stats.put(AppConstants.KEY_RESULTADO, "PROFITABLE");
        return stats;
    }

    private String buildStatsCsvRow(String symbol, String timeframe) {
        return symbol + "," + timeframe + ",10,4,14,0.08,80.00,0.15,0.15,"
                + "71.4%,1.95,2024-01-01,2024-12-31,PROFITABLE";
    }

    private Path buildModelBacktestPath(String timeframe, String modelName, String symbol) {
        return tempDir.resolve(String.format("backtest_result_%s_%s_%s.csv", timeframe, modelName, symbol));
    }
}
