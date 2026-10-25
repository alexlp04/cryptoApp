package com.bottrading.market.application;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import com.bottrading.market.application.port.out.VelaRepositoryPort;
import com.bottrading.market.domain.Vela;
import com.bottrading.shared.exceptions.DataFetchException;
import com.bottrading.shared.utils.ConsoleLoader;
import com.bottrading.trading.infrastructure.bridge.PythonBridgeExecutionException;
import com.bottrading.trading.infrastructure.bridge.PythonBridgeFacade;
import com.bottrading.trading.infrastructure.bridge.PythonBridgeRequest;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FetchServiceTest {

    @Mock
    private VelaRepositoryPort velaRepo;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private PythonBridgeFacade pythonBridgeFacade;

    @InjectMocks
    private FetchService fetchService;

    // ─── Constantes de test ──────────────────────────────────────────────────
    private static final String SYMBOL = "BTCUSDT";
    private static final String INTERVAL = "1h";
    private static final long INTERVAL_MILLIS = 3_600_000L;

    // ─── Helpers ─────────────────────────────────────────────────────────────
    private Vela crearVelaTest(String symbol) {
        return new Vela(
                symbol, INTERVAL, 1704067200000L,
                new BigDecimal("40000.00"), new BigDecimal("40500.00"),
                new BigDecimal("39500.00"), new BigDecimal("40200.00"),
                new BigDecimal("100.0"), 1704070800000L,
                new BigDecimal("4020000.0"), 150,
                new BigDecimal("50.0"), new BigDecimal("2010000.0"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CONSTRUCCIÓN
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("CONSTRUCCIÓN: Instanciación del servicio")
    class ConstructionTests {

        @Test
        @DisplayName("✓ La instancia no debe ser nula con todas las dependencias inyectadas")
        void should_create_non_null_instance_when_all_dependencies_provided() {
            assertThat(fetchService, is(notNullValue()));
        }

        @Test
        @DisplayName("✓ Debe aceptar las tres dependencias por constructor")
        void should_accept_constructor_with_three_dependencies() {
            FetchService sut = new FetchService(velaRepo, jdbcTemplate, pythonBridgeFacade);
            assertThat(sut, is(notNullValue()));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // fetch() — DESCARGA INCREMENTAL PRINCIPAL
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("fetch() — Descarga incremental principal")
    class FetchTests {

        @Test
        @DisplayName("✓ Debe llamar callPythonAndSave con null cuando no hay datos previos")
        void should_call_python_with_null_timestamp_when_no_previous_data() throws PythonBridgeExecutionException {
            ConsoleLoader consoleMock = mock(ConsoleLoader.class);
            try (MockedStatic<ConsoleLoader> cs = mockStatic(ConsoleLoader.class)) {
                cs.when(ConsoleLoader::getInstance).thenReturn(consoleMock);
                // lastTimestamp null → descarga completa
                doReturn(null).when(velaRepo).findMaxOpenTimeBySymbolAndInterval(SYMBOL, INTERVAL);
                doReturn(50).when(pythonBridgeFacade).execute(any());

                fetchService.fetch(SYMBOL, INTERVAL);

                verify(pythonBridgeFacade, times(1)).execute(any(PythonBridgeRequest.class));
            }
        }

        @Test
        @DisplayName("✓ No debe llamar Python cuando los datos ya están al día")
        void should_not_call_python_when_data_is_already_up_to_date() throws PythonBridgeExecutionException {
            long now = System.currentTimeMillis();
            long lastTimestamp = now - (INTERVAL_MILLIS / 2); // dentro del intervalo actual

            ConsoleLoader consoleMock = mock(ConsoleLoader.class);
            try (MockedStatic<ConsoleLoader> cs = mockStatic(ConsoleLoader.class)) {
                cs.when(ConsoleLoader::getInstance).thenReturn(consoleMock);
                doReturn(lastTimestamp).when(velaRepo).findMaxOpenTimeBySymbolAndInterval(SYMBOL, INTERVAL);
                doReturn(lastTimestamp - INTERVAL_MILLIS * 10)
                        .when(velaRepo).findMinOpenTimeBySymbolAndInterval(SYMBOL, INTERVAL);
                // Sin huecos: count == expected
                doReturn(11L).when(velaRepo).countBySymbolAndIntervalAndOpenTimeBetween(
                        anyString(), anyString(), anyLong(), anyLong());
                doReturn(null).when(velaRepo).findFirstInternalGapOpenTime(
                        anyString(), anyString(), anyLong(), anyLong(), anyLong());

                fetchService.fetch(SYMBOL, INTERVAL);

                verify(pythonBridgeFacade, never()).execute(any());
            }
        }

        @Test
        @DisplayName("✓ Debe lanzar DataFetchException cuando el bridge Python falla")
        void should_throw_dataFetchException_when_python_bridge_fails() throws PythonBridgeExecutionException {
            ConsoleLoader consoleMock = mock(ConsoleLoader.class);
            try (MockedStatic<ConsoleLoader> cs = mockStatic(ConsoleLoader.class)) {
                cs.when(ConsoleLoader::getInstance).thenReturn(consoleMock);
                doReturn(null).when(velaRepo).findMaxOpenTimeBySymbolAndInterval(SYMBOL, INTERVAL);
                doThrow(new PythonBridgeExecutionException("Error de conexión Python"))
                        .when(pythonBridgeFacade).execute(any());

                assertThrows(DataFetchException.class, () -> fetchService.fetch(SYMBOL, INTERVAL));
            }
        }

        @Test
        @DisplayName("✓ Debe llamar callPythonAndSave cuando los datos están desactualizados")
        void should_call_python_when_data_is_stale() throws PythonBridgeExecutionException {
            long staleTimestamp = System.currentTimeMillis() - INTERVAL_MILLIS * 3; // 3 intervalos atrasado

            ConsoleLoader consoleMock = mock(ConsoleLoader.class);
            try (MockedStatic<ConsoleLoader> cs = mockStatic(ConsoleLoader.class)) {
                cs.when(ConsoleLoader::getInstance).thenReturn(consoleMock);
                doReturn(staleTimestamp).when(velaRepo).findMaxOpenTimeBySymbolAndInterval(SYMBOL, INTERVAL);
                doReturn(staleTimestamp - INTERVAL_MILLIS * 10)
                        .when(velaRepo).findMinOpenTimeBySymbolAndInterval(SYMBOL, INTERVAL);
                // Sin huecos históricos
                doReturn(11L).when(velaRepo).countBySymbolAndIntervalAndOpenTimeBetween(
                        anyString(), anyString(), anyLong(), anyLong());
                doReturn(null).when(velaRepo).findFirstInternalGapOpenTime(
                        anyString(), anyString(), anyLong(), anyLong(), anyLong());
                doReturn(10).when(pythonBridgeFacade).execute(any());

                fetchService.fetch(SYMBOL, INTERVAL);

                verify(pythonBridgeFacade, times(1)).execute(any(PythonBridgeRequest.class));
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // fullRefresh() — DESCARGA DESDE CERO
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("fullRefresh() — Eliminación y recarga de histórico")
    class FullRefreshTests {

        @Test
        @DisplayName("✓ Debe eliminar las velas antes de descargar cuando days es null")
        void should_delete_velas_when_days_is_null() throws PythonBridgeExecutionException {
            long minTimestamp = 1704067200000L;

            ConsoleLoader consoleMock = mock(ConsoleLoader.class);
            try (MockedStatic<ConsoleLoader> cs = mockStatic(ConsoleLoader.class)) {
                cs.when(ConsoleLoader::getInstance).thenReturn(consoleMock);
                doReturn(minTimestamp).when(velaRepo).findMinOpenTimeBySymbolAndInterval(SYMBOL, INTERVAL);
                doReturn(5).when(pythonBridgeFacade).execute(any());

                fetchService.fullRefresh(SYMBOL, INTERVAL, null);
                verify(velaRepo, times(1))
                        .deleteBySymbolAndIntervalAndOpenTimeGreaterThanEqual(SYMBOL, INTERVAL, minTimestamp);
            }
        }

        @Test
        @DisplayName("✓ No debe borrar nada cuando no existe historial previo (minTimestamp null)")
        void should_not_delete_when_no_previous_data_exists() throws PythonBridgeExecutionException {
            ConsoleLoader consoleMock = mock(ConsoleLoader.class);
            try (MockedStatic<ConsoleLoader> cs = mockStatic(ConsoleLoader.class)) {
                cs.when(ConsoleLoader::getInstance).thenReturn(consoleMock);
                doReturn(null).when(velaRepo).findMinOpenTimeBySymbolAndInterval(SYMBOL, INTERVAL);
                doReturn(0).when(pythonBridgeFacade).execute(any());

                fetchService.fullRefresh(SYMBOL, INTERVAL, null);
                verify(velaRepo, never())
                        .deleteBySymbolAndIntervalAndOpenTimeGreaterThanEqual(anyString(), anyString(), anyLong());
            }
        }

        @Test
        @DisplayName("✓ Debe usar ventana de días calculada cuando days no es null")
        void should_use_days_window_as_deletion_anchor_when_days_provided() throws PythonBridgeExecutionException {
            ConsoleLoader consoleMock = mock(ConsoleLoader.class);
            try (MockedStatic<ConsoleLoader> cs = mockStatic(ConsoleLoader.class)) {
                cs.when(ConsoleLoader::getInstance).thenReturn(consoleMock);
                doReturn(5).when(pythonBridgeFacade).execute(any());

                fetchService.fullRefresh(SYMBOL, INTERVAL, 30);

                // Con days=30, el anchor es calculado internamente y se pasa al borrado
                verify(velaRepo, times(1))
                        .deleteBySymbolAndIntervalAndOpenTimeGreaterThanEqual(eq(SYMBOL), eq(INTERVAL), anyLong());
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // findOldestTimestamp() — CONSULTA DE TIMESTAMP MÁS ANTIGUO
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("findOldestTimestamp() — Consulta del timestamp más antiguo")
    class FindOldestTimestampTests {

        @Test
        @DisplayName("✓ Debe retornar null cuando no hay datos en la base de datos")
        void should_return_null_when_no_data_exists() {
            doReturn(null).when(velaRepo).findMinOpenTimeBySymbolAndInterval(SYMBOL, INTERVAL);

            LocalDateTime result = fetchService.findOldestTimestamp(SYMBOL, INTERVAL);

            assertThat(result, is(nullValue()));
        }

        @Test
        @DisplayName("✓ Debe convertir epoch millis a LocalDateTime correctamente")
        void should_convert_epoch_millis_to_local_date_time() {
            long epochMs = 1704067200000L; // 2024-01-01 00:00:00 UTC
            doReturn(epochMs).when(velaRepo).findMinOpenTimeBySymbolAndInterval(SYMBOL, INTERVAL);

            LocalDateTime result = fetchService.findOldestTimestamp(SYMBOL, INTERVAL);

            assertThat(result, is(notNullValue()));
            assertThat(result.getYear(), is(2024));
            assertThat(result.getMonthValue(), is(1));
            assertThat(result.getDayOfMonth(), is(1));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // fetchRange() — DESCARGA EN RANGO EXACTO
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("fetchRange() — Descarga en rango exacto")
    class FetchRangeTests {

        @Test
        @DisplayName("✓ Debe lanzar IllegalArgumentException cuando symbol es nulo")
        void should_throw_when_symbol_is_null() {
            LocalDateTime from = LocalDateTime.of(2024, 1, 1, 0, 0);
            LocalDateTime to = LocalDateTime.of(2024, 1, 31, 0, 0);

            assertThrows(IllegalArgumentException.class,
                    () -> fetchService.fetchRange(null, INTERVAL, from, to));
        }

        @Test
        @DisplayName("✓ Debe lanzar IllegalArgumentException cuando symbol está vacío")
        void should_throw_when_symbol_is_blank() {
            LocalDateTime from = LocalDateTime.of(2024, 1, 1, 0, 0);
            LocalDateTime to = LocalDateTime.of(2024, 1, 31, 0, 0);

            assertThrows(IllegalArgumentException.class,
                    () -> fetchService.fetchRange("", INTERVAL, from, to));
        }

        @Test
        @DisplayName("✓ Debe lanzar IllegalArgumentException cuando timeframe es nulo")
        void should_throw_when_timeframe_is_null() {
            LocalDateTime from = LocalDateTime.of(2024, 1, 1, 0, 0);
            LocalDateTime to = LocalDateTime.of(2024, 1, 31, 0, 0);

            assertThrows(IllegalArgumentException.class,
                    () -> fetchService.fetchRange(SYMBOL, null, from, to));
        }

        @Test
        @DisplayName("✓ Debe retornar 0 cuando 'to' es anterior a 'from'")
        void should_return_zero_when_to_is_before_from() {
            LocalDateTime from = LocalDateTime.of(2024, 1, 31, 0, 0);
            LocalDateTime to = LocalDateTime.of(2024, 1, 1, 0, 0); // to < from

            int result = fetchService.fetchRange(SYMBOL, INTERVAL, from, to);

            assertThat(result, is(0));
        }

        @Test
        @DisplayName("✓ Debe lanzar IllegalArgumentException cuando from o to son nulos")
        void should_throw_when_from_is_null() {
            assertThrows(IllegalArgumentException.class,
                    () -> fetchService.fetchRange(SYMBOL, INTERVAL, null, LocalDateTime.of(2024, 1, 1, 0, 0)));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // fetchIncremental() — DESCARGA INCREMENTAL PARA ENTRENAMIENTO
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("fetchIncremental() — Descarga incremental para entrenamiento")
    class FetchIncrementalTests {

        @Test
        @DisplayName("✓ Debe descargar completamente cuando no hay datos previos")
        void should_download_fully_when_no_previous_data() throws PythonBridgeExecutionException {
            long now = System.currentTimeMillis();
            int dias = 7;

            ConsoleLoader consoleMock = mock(ConsoleLoader.class);
            try (MockedStatic<ConsoleLoader> cs = mockStatic(ConsoleLoader.class)) {
                cs.when(ConsoleLoader::getInstance).thenReturn(consoleMock);
                doReturn(null).when(velaRepo).findMaxOpenTimeBySymbolAndInterval(SYMBOL, INTERVAL);
                doReturn(50).when(pythonBridgeFacade).execute(any());

                fetchService.fetchIncremental(SYMBOL, INTERVAL, dias, now);

                verify(pythonBridgeFacade, times(1)).execute(any(PythonBridgeRequest.class));
            }
        }

        @Test
        @DisplayName("✓ Debe retornar lastTimestamp y no llamar Python cuando el historial está completo y al día")
        void should_return_last_timestamp_when_history_is_complete_and_up_to_date() throws PythonBridgeExecutionException {
            long now = System.currentTimeMillis();
            int dias = 7;
            long target = now - dias * 24L * 60 * 60 * 1000L;
            // lastTimestamp dentro del intervalo actual → fetchFrom = lastTimestamp+interval > now → no descarga
            long lastTimestamp = now - (INTERVAL_MILLIS / 2);

            ConsoleLoader consoleMock = mock(ConsoleLoader.class);
            try (MockedStatic<ConsoleLoader> cs = mockStatic(ConsoleLoader.class)) {
                cs.when(ConsoleLoader::getInstance).thenReturn(consoleMock);
                doReturn(lastTimestamp).when(velaRepo).findMaxOpenTimeBySymbolAndInterval(SYMBOL, INTERVAL);
                // Sin huecos en la ventana: count >= expected
                long count = ((lastTimestamp - target) / INTERVAL_MILLIS) + 1;
                doReturn(count).when(velaRepo).countBySymbolAndIntervalAndOpenTimeBetween(
                        anyString(), anyString(), anyLong(), anyLong());
                doReturn(null).when(velaRepo).findFirstInternalGapOpenTime(
                        anyString(), anyString(), anyLong(), anyLong(), anyLong());

                long result = fetchService.fetchIncremental(SYMBOL, INTERVAL, dias, now);

                assertThat(result, is(lastTimestamp));
                verify(pythonBridgeFacade, never()).execute(any());
            }
        }

        @Test
        @DisplayName("✓ Debe descargar desde el hueco cuando se detecta un gap")
        void should_download_from_gap_when_gap_detected() throws PythonBridgeExecutionException {
            long now = System.currentTimeMillis();
            int dias = 7;
            long target = now - dias * 24L * 60 * 60 * 1000L;
            long lastTimestamp = now - INTERVAL_MILLIS;
            long gapTimestamp = target + INTERVAL_MILLIS * 5;

            ConsoleLoader consoleMock = mock(ConsoleLoader.class);
            try (MockedStatic<ConsoleLoader> cs = mockStatic(ConsoleLoader.class)) {
                cs.when(ConsoleLoader::getInstance).thenReturn(consoleMock);
                doReturn(lastTimestamp).when(velaRepo).findMaxOpenTimeBySymbolAndInterval(SYMBOL, INTERVAL);
                // Hay menos velas de las esperadas → hueco
                doReturn(1L).when(velaRepo).countBySymbolAndIntervalAndOpenTimeBetween(
                        anyString(), anyString(), anyLong(), anyLong());
                doReturn(gapTimestamp).when(velaRepo).findMinOpenTimeBySymbolAndIntervalAndOpenTimeBetween(
                        anyString(), anyString(), anyLong(), anyLong());
                doReturn(null).when(velaRepo).findFirstInternalGapOpenTime(
                        anyString(), anyString(), anyLong(), anyLong(), anyLong());
                doReturn(30).when(pythonBridgeFacade).execute(any());

                fetchService.fetchIncremental(SYMBOL, INTERVAL, dias, now);

                verify(pythonBridgeFacade, times(1)).execute(any(PythonBridgeRequest.class));
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // getIntervalMillis() — UTILIDAD ESTÁTICA
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("getIntervalMillis() — Conversión de intervalos a milisegundos")
    class GetIntervalMillisTests {

        @Test
        @DisplayName("✓ Debe retornar 60000 para intervalo '1m'")
        void should_return_60000_for_1m() {
            assertThat(FetchService.getIntervalMillis("1m"), is(60_000L));
        }

        @Test
        @DisplayName("✓ Debe retornar 300000 para intervalo '5m'")
        void should_return_300000_for_5m() {
            assertThat(FetchService.getIntervalMillis("5m"), is(300_000L));
        }

        @Test
        @DisplayName("✓ Debe retornar 3600000 para intervalo '1h'")
        void should_return_3600000_for_1h() {
            assertThat(FetchService.getIntervalMillis("1h"), is(3_600_000L));
        }

        @Test
        @DisplayName("✓ Debe retornar 14400000 para intervalo '4h'")
        void should_return_14400000_for_4h() {
            assertThat(FetchService.getIntervalMillis("4h"), is(14_400_000L));
        }

        @Test
        @DisplayName("✓ Debe retornar 86400000 para intervalo '1d'")
        void should_return_86400000_for_1d() {
            assertThat(FetchService.getIntervalMillis("1d"), is(86_400_000L));
        }

        @Test
        @DisplayName("✓ Un intervalo desconocido falla en vez de caer a 1 minuto (#84)")
        void should_throw_for_unknown_interval() {
            assertThrows(IllegalArgumentException.class, () -> FetchService.getIntervalMillis("unknown"));
            assertThrows(IllegalArgumentException.class, () -> FetchService.getIntervalMillis("30m"));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // fillGapRange() — DELEGACIÓN A fetchRange
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("fillGapRange() — Relleno de huecos con rango exacto")
    class FillGapRangeTests {

        @Test
        @DisplayName("✓ Debe completar sin error cuando to < from (rango invertido)")
        void should_delegate_to_fetch_range_with_converted_timestamps() {
            long from = 1704153600000L; // 2024-01-02 00:00:00 UTC
            long to = 1704067200000L;   // 2024-01-01 00:00:00 UTC — to < from

            // fillGapRange convierte a LocalDateTime y llama fetchRange.
            // fetchRange con to.isBefore(from) retorna 0 inmediatamente sin red ni BD.
            fetchService.fillGapRange(SYMBOL, INTERVAL, from, to);

            assertThat(fetchService, is(notNullValue()));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CONTRATO DE PUERTOS
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("Contrato de interfaz FetchMarketDataUseCase")
    class ContratoPuertoEntradaTests {

        @Test
        @DisplayName("✓ FetchService debe implementar FetchMarketDataUseCase")
        void should_implement_fetchMarketDataUseCase_interface() {
            assertThat(fetchService instanceof
                    com.bottrading.market.application.port.in.FetchMarketDataUseCase,
                    is(true));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Huecos reales, rangos con páginas vacías e interrupciones (#116 #118)
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("Convergencia de huecos y fetchRange")
    class HuecosYRangosTests {

        private com.google.gson.JsonArray velas(long... openTimes) {
            com.google.gson.JsonArray array = new com.google.gson.JsonArray();
            for (long t : openTimes) {
                array.add(com.google.gson.JsonParser.parseString("[" + t + ",\"1\",\"2\",\"0.5\",\"1.5\",\"10\","
                        + (t + 3_599_999) + ",\"100\",5,\"3\",\"4\"]"));
            }
            return array;
        }

        @Test
        @DisplayName("✓ Un hueco ya pedido a Binance no se vuelve a descargar en la misma ejecución (#116)")
        void already_requested_gap_is_not_downloaded_again() throws PythonBridgeExecutionException {
            long now = System.currentTimeMillis();
            int dias = 30;
            long windowStart = now - dias * 24L * 60 * 60 * 1000L;
            long lastTimestamp = now - (INTERVAL_MILLIS / 2); // al día: sin cola nueva que descargar
            // el par es más joven que la ventana: la primera vela está lejos de windowStart
            long primera = windowStart + 10 * INTERVAL_MILLIS;

            ConsoleLoader consoleMock = mock(ConsoleLoader.class);
            try (MockedStatic<ConsoleLoader> cs = mockStatic(ConsoleLoader.class)) {
                cs.when(ConsoleLoader::getInstance).thenReturn(consoleMock);
                doReturn(lastTimestamp).when(velaRepo).findMaxOpenTimeBySymbolAndInterval(SYMBOL, INTERVAL);
                doReturn(primera).when(velaRepo).findMinOpenTimeBySymbolAndIntervalAndOpenTimeBetween(
                        anyString(), anyString(), anyLong(), anyLong());
                doReturn(null).when(velaRepo).findFirstInternalGapOpenTime(
                        anyString(), anyString(), anyLong(), anyLong(), anyLong());
                doReturn(30).when(pythonBridgeFacade).execute(any());

                fetchService.fetchIncremental(SYMBOL, INTERVAL, dias, now);
                fetchService.fetchIncremental(SYMBOL, INTERVAL, dias, now);

                verify(pythonBridgeFacade, times(1)).execute(any(PythonBridgeRequest.class));
            }
        }

        @Test
        @DisplayName("✓ fetchRange sigue con la página siguiente cuando una está vacía (#118)")
        void fetch_range_continues_after_an_empty_page() {
            FetchService spy = org.mockito.Mockito.spy(fetchService);
            long t0 = 1_700_000_000_000L - (1_700_000_000_000L % INTERVAL_MILLIS);
            LocalDateTime from = LocalDateTime.ofEpochSecond(t0 / 1000, 0, java.time.ZoneOffset.UTC);
            LocalDateTime to = from.plusHours(2_500);
            doReturn(velas()).doReturn(velas(t0 + 1_500 * INTERVAL_MILLIS)).doReturn(velas())
                    .when(spy).requestKlines(any(), anyString(), anyString(), anyLong(), anyLong());
            doReturn(new int[] {1}).when(jdbcTemplate).batchUpdate(anyString(), org.mockito.ArgumentMatchers.anyList());

            int inserted = spy.fetchRange(SYMBOL, INTERVAL, from, to);

            assertThat(inserted, is(1));
            verify(spy, times(3)).requestKlines(any(), anyString(), anyString(), anyLong(), anyLong());
        }

        @SuppressWarnings("unchecked")
        private java.net.http.HttpResponse<String> respuesta(int status, String retryAfter, String body) {
            java.net.http.HttpResponse<String> r = mock(java.net.http.HttpResponse.class);
            doReturn(status).when(r).statusCode();
            doReturn(body).when(r).body();
            java.util.Map<String, java.util.List<String>> cabeceras =
                    retryAfter == null ? java.util.Map.of() : java.util.Map.of("Retry-After", java.util.List.of(retryAfter));
            doReturn(java.net.http.HttpHeaders.of(cabeceras, (a, b) -> true)).when(r).headers();
            return r;
        }

        @Test
        @DisplayName("✓ Un 429 se reintenta esperando el Retry-After de Binance (#118)")
        void rate_limit_is_retried_honouring_retry_after() throws Exception {
            FetchService spy = org.mockito.Mockito.spy(fetchService);
            org.mockito.Mockito.doNothing().when(spy).pausar(anyLong());
            java.net.http.HttpClient client = mock(java.net.http.HttpClient.class);
            java.net.http.HttpResponse<String> limitada = respuesta(429, "7", "");
            java.net.http.HttpResponse<String> ok = respuesta(200, null, "[]");
            doReturn(limitada).doReturn(ok).when(client).send(any(), any());

            assertThat(spy.requestKlines(client, SYMBOL, INTERVAL, 0L, 1L).isEmpty(), is(true));

            verify(spy).pausar(7_000L);
        }

        @Test
        @DisplayName("✓ Un 5xx persistente falla tras los reintentos y un 400 no se reintenta (#118)")
        void persistent_server_error_gives_up_and_client_error_is_not_retried() throws Exception {
            FetchService spy = org.mockito.Mockito.spy(fetchService);
            org.mockito.Mockito.doNothing().when(spy).pausar(anyLong());
            java.net.http.HttpClient caido = mock(java.net.http.HttpClient.class);
            java.net.http.HttpResponse<String> r503 = respuesta(503, null, "");
            doReturn(r503).when(caido).send(any(), any());
            java.net.http.HttpClient malo = mock(java.net.http.HttpClient.class);
            java.net.http.HttpResponse<String> r400 = respuesta(400, null, "");
            doReturn(r400).when(malo).send(any(), any());

            assertThrows(com.bottrading.shared.exceptions.DataFetchException.class,
                    () -> spy.requestKlines(caido, SYMBOL, INTERVAL, 0L, 1L));
            assertThrows(com.bottrading.shared.exceptions.DataFetchException.class,
                    () -> spy.requestKlines(malo, SYMBOL, INTERVAL, 0L, 1L));

            verify(caido, times(3)).send(any(), any());
            verify(malo, times(1)).send(any(), any());
        }

        @Test
        @DisplayName("✓ Si una página falla, lo ya descargado se guarda y el error dice hasta dónde se llegó (#118)")
        void failure_keeps_downloaded_candles_and_reports_progress() {
            FetchService spy = org.mockito.Mockito.spy(fetchService);
            long t0 = 1_700_000_000_000L - (1_700_000_000_000L % INTERVAL_MILLIS);
            LocalDateTime from = LocalDateTime.ofEpochSecond(t0 / 1000, 0, java.time.ZoneOffset.UTC);
            doReturn(velas(t0)).doThrow(new com.bottrading.shared.exceptions.DataFetchException("Binance returned HTTP 503"))
                    .when(spy).requestKlines(any(), anyString(), anyString(), anyLong(), anyLong());
            doReturn(new int[] {1}).when(jdbcTemplate).batchUpdate(anyString(), org.mockito.ArgumentMatchers.anyList());

            com.bottrading.shared.exceptions.DataFetchException e = assertThrows(
                    com.bottrading.shared.exceptions.DataFetchException.class,
                    () -> spy.fetchRange(SYMBOL, INTERVAL, from, from.plusHours(2_500)));

            assertThat(e.getMessage().contains("1 velas guardadas"), is(true));
            verify(jdbcTemplate).batchUpdate(anyString(), org.mockito.ArgumentMatchers.anyList());
        }

        @Test
        @DisplayName("✓ Un IOException de red no deja el hilo marcado como interrumpido (#118)")
        void io_error_does_not_set_the_interrupt_flag() throws Exception {
            java.net.http.HttpClient client = mock(java.net.http.HttpClient.class);
            doThrow(new java.io.IOException("timeout")).when(client).send(any(), any());

            assertThrows(com.bottrading.shared.exceptions.DataFetchException.class,
                    () -> fetchService.requestKlines(client, SYMBOL, INTERVAL, 0L, 1L));

            assertThat(Thread.currentThread().isInterrupted(), is(false));
        }
    }

    @Test
    @DisplayName("✓ Un frame ERROR de Python lanza DataFetchException con su mensaje (#81)")
    void python_error_frame_is_a_fetch_failure() {
        java.util.Map<String, Object> envelope = java.util.Map.of(
                "message_type", "ERROR", "payload", java.util.Map.of("status", "error", "message", "símbolo inválido"));

        org.junit.jupiter.api.Assertions.assertTrue(
                assertThrows(com.bottrading.shared.exceptions.DataFetchException.class,
                        () -> FetchService.lanzarSiEsError(envelope)).getMessage().contains("símbolo inválido"));
        FetchService.lanzarSiEsError(java.util.Map.of("message_type", "FETCH_CHUNK", "payload", java.util.Map.of()));
    }
}
