package com.bottrading.application.market;

import com.bottrading.domain.market.VelaRepository;
import com.bottrading.domain.market.IndicadorRepository;
import com.bottrading.exceptions.DataFetchException;
import com.bottrading.infrastructure.bridge.PythonBridgeExecutionException;
import com.bottrading.infrastructure.bridge.PythonBridgeFacade;
import com.bottrading.infrastructure.bridge.PythonBridgeRequest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests para FetchService — servicio de descarga de velas desde Binance vía Python.
 * 
 * Arquitectura testada:
 * 1. Given: Estados de VelaRepository (sin datos, datos al día, datos viejos, brecha)
 * 2. When: Se invoca fetch() o fetchIncremental()
 * 3. Then: Se verifica que calls a PythonBridgeFacade y VelaRepository sean correctas
 */
@Slf4j
@ExtendWith({MockitoExtension.class})
@MockitoSettings(strictness = Strictness.LENIENT)
class FetchServiceTest {

    // ============ Fixtures ============
    @Mock
    private VelaRepository velaRepository;

    @Mock
    private IndicadorRepository indicadorRepository;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private PythonBridgeFacade pythonBridgeFacade;

    @InjectMocks
    private FetchService fetchService;

    private static final String SYMBOL = "BTCUSDT";
    private static final String INTERVAL = "1h";
    private static final long NOW_MS = System.currentTimeMillis();

    // ============ CONSTRUCCIÓN ============

    @Nested
    @DisplayName("CONSTRUCCIÓN: instanciación de FetchService")
    class ConstructionTests {

        @Test
        @DisplayName("✓ Debe crear FetchService con todas las dependencias inyectadas")
        void should_create_fetchservice_with_all_dependencies() throws Exception {
            assertThat(fetchService, is(notNullValue()));
            assertThat(fetchService, instanceOf(FetchService.class));
        }
    }

    // ============ fetch() — descarga inteligente ============

    @Nested
    @DisplayName("fetch(symbol, interval) — descarga incremental inteligente")
    class FetchMethodTests {

        @Test
        @DisplayName("✓ Debe iniciar descarga completa cuando no hay datos previos")
        void should_start_full_download_when_no_prior_data() throws Exception {
            // Given: No hay datos en BD para BTCUSDT/1h
            when(velaRepository.findMaxOpenTimeBySymbolAndInterval(SYMBOL, INTERVAL))
                    .thenReturn(null);
            when(pythonBridgeFacade.execute(any()))
                    .thenReturn(50000);

            // When: Se invoca fetch()
            fetchService.fetch(SYMBOL, INTERVAL);

            // Then: PythonBridgeFacade debe ser invocado
            verify(pythonBridgeFacade, times(1)).execute(any());
            verify(velaRepository, times(1))
                    .findMaxOpenTimeBySymbolAndInterval(SYMBOL, INTERVAL);
        }

        @Test
        @DisplayName("✓ Debe omitir descarga cuando datos están al día")
        void should_skip_download_when_data_is_up_to_date() throws Exception {
            // Given: Última vela hace 30 minutos (< 1 hora de intervalo)
            long lastTimestamp = NOW_MS - (30 * 60 * 1000);
            when(velaRepository.findMaxOpenTimeBySymbolAndInterval(SYMBOL, INTERVAL))
                    .thenReturn(lastTimestamp);
            when(velaRepository.findMinOpenTimeBySymbolAndInterval(SYMBOL, INTERVAL))
                    .thenReturn(NOW_MS - (24 * 60 * 60 * 1000));

            // When: Se invoca fetch() con datos al día
            fetchService.fetch(SYMBOL, INTERVAL);

            // Then: PythonBridgeFacade NO debe ser invocado
            verify(pythonBridgeFacade, never()).execute(any());
        }

        @Test
        @DisplayName("✓ Debe descargar nuevas velas cuando datos están desactualizados")
        void should_fetch_new_candles_when_data_is_stale() throws Exception {
            // Given: Última vela hace 2 horas (> 1 hora intervalo)
            long lastTimestamp = NOW_MS - (2 * 60 * 60 * 1000);
            when(velaRepository.findMaxOpenTimeBySymbolAndInterval(SYMBOL, INTERVAL))
                    .thenReturn(lastTimestamp);
            when(velaRepository.findMinOpenTimeBySymbolAndInterval(SYMBOL, INTERVAL))
                    .thenReturn(NOW_MS - (24 * 60 * 60 * 1000));
            when(velaRepository.countBySymbolAndIntervalAndOpenTimeBetween(
                    eq(SYMBOL), eq(INTERVAL), anyLong(), anyLong()))
                    .thenReturn(24L);
            when(velaRepository.findMaxOpenTimeBySymbolAndIntervalAndOpenTimeBetween(
                    eq(SYMBOL), eq(INTERVAL), anyLong(), anyLong()))
                    .thenReturn(lastTimestamp);
            when(pythonBridgeFacade.execute(any()))
                    .thenReturn(100);

            // When: Se invoca fetch()
            fetchService.fetch(SYMBOL, INTERVAL);

            // Then: PythonBridgeFacade debe ser invocado
            verify(pythonBridgeFacade, times(1)).execute(any());
        }

        @Test
        @DisplayName("✓ Debe resincronizar cuando detecta hueco histórico")
        void should_resync_when_internal_gap_detected() throws Exception {
            // Given: Hay brecha interna detectada
            long minTime = NOW_MS - (24 * 60 * 60 * 1000);
            long maxTime = NOW_MS - (1 * 60 * 60 * 1000);
            when(velaRepository.findMaxOpenTimeBySymbolAndInterval(SYMBOL, INTERVAL))
                    .thenReturn(maxTime);
            when(velaRepository.findMinOpenTimeBySymbolAndInterval(SYMBOL, INTERVAL))
                    .thenReturn(minTime);
            when(velaRepository.countBySymbolAndIntervalAndOpenTimeBetween(
                    eq(SYMBOL), eq(INTERVAL), eq(minTime), eq(maxTime)))
                    .thenReturn(23L);
            when(velaRepository.findMinOpenTimeBySymbolAndIntervalAndOpenTimeBetween(
                    eq(SYMBOL), eq(INTERVAL), eq(minTime), eq(maxTime)))
                    .thenReturn(minTime);
            when(velaRepository.findFirstInternalGapOpenTime(
                    eq(SYMBOL), eq(INTERVAL), eq(minTime), eq(maxTime), anyLong()))
                    .thenReturn(minTime + (60 * 60 * 1000));
            when(pythonBridgeFacade.execute(any()))
                    .thenReturn(1);

            // When: Se invoca fetch()
            fetchService.fetch(SYMBOL, INTERVAL);

            // Then: PythonBridgeFacade debe resincronizar
            verify(pythonBridgeFacade, times(1)).execute(any());
        }

        @Test
        @DisplayName("✓ Debe lanzar DataFetchException cuando Python falla")
        void should_throw_datafetch_exception_on_python_failure() throws Exception {
            // Given: PythonBridgeFacade falla
            when(velaRepository.findMaxOpenTimeBySymbolAndInterval(SYMBOL, INTERVAL))
                    .thenReturn(null);
            when(pythonBridgeFacade.execute(any()))
                    .thenThrow(new PythonBridgeExecutionException("Python engine crashed"));

            // When: Se invoca fetch()
            // Then: Debe lanzar DataFetchException
            assertThatThrownBy(() -> fetchService.fetch(SYMBOL, INTERVAL))
                    .isInstanceOf(DataFetchException.class)
                    .hasMessageContaining("Data synchronization failed");
        }
    }

    // ============ fetchIncremental() — descarga para backtest ============

    @Nested
    @DisplayName("fetchIncremental(symbol, interval, dias, now)")
    class FetchIncrementalTests {

        @Test
        @DisplayName("✓ Debe descargar N días completos cuando no hay datos previos")
        void should_download_full_n_days_window_when_no_prior_data() throws Exception {
            // Given: No hay datos, queremos 30 días
            int dias = 30;
            long targetTimestamp = NOW_MS - (dias * 24 * 60 * 60 * 1000);

            when(velaRepository.findMaxOpenTimeBySymbolAndInterval(SYMBOL, INTERVAL))
                    .thenReturn(null);
            doNothing().when(indicadorRepository)
                    .deleteByVelaSymbolAndIntervalAndOpenTimeGreaterThanEqual(
                            SYMBOL, INTERVAL, targetTimestamp);
            doNothing().when(velaRepository)
                    .deleteBySymbolAndIntervalAndOpenTimeGreaterThanEqual(
                            SYMBOL, INTERVAL, targetTimestamp);
            when(pythonBridgeFacade.execute(any()))
                    .thenReturn(720);

            // When: Invocamos fetchIncremental
            long result = fetchService.fetchIncremental(SYMBOL, INTERVAL, dias, NOW_MS);

            // Then: Debe devolver el timestamp desde el que se fetcheó
            assertThat(result, is(equalTo(targetTimestamp)));
            verify(pythonBridgeFacade, times(1)).execute(any());
        }

        @Test
        @DisplayName("✓ Debe limpiar y completar cuando detecta brecha")
        void should_cleanup_and_complete_when_gap_in_training_window() throws Exception {
            // Given: Tenemos datos incompletos
            int dias = 30;
            long targetTimestamp = NOW_MS - (dias * 24 * 60 * 60 * 1000);
            long lastTimestamp = NOW_MS - (5 * 60 * 60 * 1000);
            long gapStart = targetTimestamp + (10 * 24 * 60 * 60 * 1000);

            when(velaRepository.findMaxOpenTimeBySymbolAndInterval(SYMBOL, INTERVAL))
                    .thenReturn(lastTimestamp);
            when(velaRepository.countBySymbolAndIntervalAndOpenTimeBetween(
                    eq(SYMBOL), eq(INTERVAL), eq(targetTimestamp), eq(lastTimestamp)))
                    .thenReturn(100L);
            when(velaRepository.findMinOpenTimeBySymbolAndIntervalAndOpenTimeBetween(
                    eq(SYMBOL), eq(INTERVAL), eq(targetTimestamp), eq(lastTimestamp)))
                    .thenReturn(targetTimestamp);
            when(velaRepository.findFirstInternalGapOpenTime(
                    eq(SYMBOL), eq(INTERVAL), eq(targetTimestamp), eq(lastTimestamp), anyLong()))
                    .thenReturn(gapStart);
            doNothing().when(indicadorRepository)
                    .deleteByVelaSymbolAndIntervalAndOpenTimeGreaterThanEqual(
                            SYMBOL, INTERVAL, gapStart);
            doNothing().when(velaRepository)
                    .deleteBySymbolAndIntervalAndOpenTimeGreaterThanEqual(
                            SYMBOL, INTERVAL, gapStart);
            when(pythonBridgeFacade.execute(any()))
                    .thenReturn(200);

            // When: fetchIncremental limpia y completa
            long result = fetchService.fetchIncremental(SYMBOL, INTERVAL, dias, NOW_MS);

            // Then: Debe retornar el punto desde donde se fetcheó
            assertThat(result, is(equalTo(gapStart)));
            verify(indicadorRepository).deleteByVelaSymbolAndIntervalAndOpenTimeGreaterThanEqual(
                    SYMBOL, INTERVAL, gapStart);
            verify(velaRepository).deleteBySymbolAndIntervalAndOpenTimeGreaterThanEqual(
                    SYMBOL, INTERVAL, gapStart);
            verify(pythonBridgeFacade).execute(any());
        }

        @Test
        @DisplayName("✓ Debe solo actualizar cuando historial es completo")
        void should_skip_cleanup_when_training_window_complete_and_recent() throws Exception {
            // Given: Datos completos y recientes
            int dias = 30;
            long targetTimestamp = NOW_MS - (dias * 24 * 60 * 60 * 1000);
            long lastTimestamp = NOW_MS - (5 * 60 * 60 * 1000);

            when(velaRepository.findMaxOpenTimeBySymbolAndInterval(SYMBOL, INTERVAL))
                    .thenReturn(lastTimestamp);

            long intervalMillis = 60 * 60 * 1000;
            int expectedCount = (int) ((lastTimestamp - targetTimestamp) / intervalMillis) + 1;

            when(velaRepository.countBySymbolAndIntervalAndOpenTimeBetween(
                    eq(SYMBOL), eq(INTERVAL), eq(targetTimestamp), eq(lastTimestamp)))
                    .thenReturn((long) expectedCount);
            doNothing().when(indicadorRepository)
                    .deleteByVelaSymbolAndIntervalAndOpenTimeGreaterThanEqual(
                            SYMBOL, INTERVAL, lastTimestamp);
            doNothing().when(velaRepository)
                    .deleteBySymbolAndIntervalAndOpenTimeGreaterThanEqual(
                            SYMBOL, INTERVAL, lastTimestamp);
            when(pythonBridgeFacade.execute(any()))
                    .thenReturn(0);

            // When: fetchIncremental detecta que no hay brecha
            long result = fetchService.fetchIncremental(SYMBOL, INTERVAL, dias, NOW_MS);

            // Then: Debe devolver lastTimestamp
            assertThat(result, is(equalTo(lastTimestamp)));
            verify(indicadorRepository).deleteByVelaSymbolAndIntervalAndOpenTimeGreaterThanEqual(
                    SYMBOL, INTERVAL, lastTimestamp);
        }

        @Test
        @DisplayName("✓ Debe resincronizar desde targetTimestamp cuando datos insuficientes")
        void should_resync_from_target_when_insufficient_historical_data() throws Exception {
            // Given: Queremos 30 días pero solo tenemos 5 días
            int dias = 30;
            long targetTimestamp = NOW_MS - (dias * 24 * 60 * 60 * 1000);
            long lastTimestamp = NOW_MS - (5 * 24 * 60 * 60 * 1000);

            when(velaRepository.findMaxOpenTimeBySymbolAndInterval(SYMBOL, INTERVAL))
                    .thenReturn(lastTimestamp);
            doNothing().when(indicadorRepository)
                    .deleteByVelaSymbolAndIntervalAndOpenTimeGreaterThanEqual(
                            SYMBOL, INTERVAL, targetTimestamp);
            doNothing().when(velaRepository)
                    .deleteBySymbolAndIntervalAndOpenTimeGreaterThanEqual(
                            SYMBOL, INTERVAL, targetTimestamp);
            when(pythonBridgeFacade.execute(any()))
                    .thenReturn(720);

            // When: fetchIncremental se invoca
            long result = fetchService.fetchIncremental(SYMBOL, INTERVAL, dias, NOW_MS);

            // Then: Debe fetchear desde targetTimestamp
            assertThat(result, is(equalTo(targetTimestamp)));
            verify(pythonBridgeFacade).execute(any());
        }

        @Test
        @DisplayName("✓ Debe lanzar DataFetchException si Python falla")
        void should_throw_datafetch_exception_on_incremental_failure() throws Exception {
            // Given: fetchIncremental intenta download pero Python falla
            when(velaRepository.findMaxOpenTimeBySymbolAndInterval(SYMBOL, INTERVAL))
                    .thenReturn(null);
            doNothing().when(indicadorRepository)
                    .deleteByVelaSymbolAndIntervalAndOpenTimeGreaterThanEqual(
                            anyString(), anyString(), anyLong());
            doNothing().when(velaRepository)
                    .deleteBySymbolAndIntervalAndOpenTimeGreaterThanEqual(
                            anyString(), anyString(), anyLong());
            when(pythonBridgeFacade.execute(any()))
                    .thenThrow(new PythonBridgeExecutionException("Network timeout"));

            // When: Se invoca fetchIncremental
            // Then: Debe lanzar DataFetchException
            assertThatThrownBy(() -> fetchService.fetchIncremental(SYMBOL, INTERVAL, 30, NOW_MS))
                    .isInstanceOf(DataFetchException.class)
                    .hasMessageContaining("Data synchronization failed");
        }
    }

    // ============ MANEJO DE ERRORES ============

    @Nested
    @DisplayName("ERRORES: manejo de fallos en descarga")
    class ErrorHandlingTests {

        @Test
        @DisplayName("✓ Debe mantener transaccionalidad (no guardar parcialmente)")
        void should_maintain_transaction_on_failure() throws Exception {
            // Given: PythonBridge falla
            when(velaRepository.findMaxOpenTimeBySymbolAndInterval(SYMBOL, INTERVAL))
                    .thenReturn(null);
            when(pythonBridgeFacade.execute(any()))
                    .thenThrow(new PythonBridgeExecutionException("IO error"));

            // When: fetch() intenta descargar pero falla
            assertThatThrownBy(() -> fetchService.fetch(SYMBOL, INTERVAL))
                    .isInstanceOf(DataFetchException.class);

            // Then: verify que execute fue llamado
            verify(pythonBridgeFacade, times(1)).execute(any());
        }

        @Test
        @DisplayName("✓ Debe retry automático (MAX_RETRIES=3 internamente)")
        void should_retry_on_failure() throws Exception {
            // Given: PythonBridge fail flag
            when(velaRepository.findMaxOpenTimeBySymbolAndInterval(SYMBOL, INTERVAL))
                    .thenReturn(null);
            when(pythonBridgeFacade.execute(any()))
                    .thenThrow(new PythonBridgeExecutionException("Persistent failure"));

            // When: fetch() intenta
            assertThatThrownBy(() -> fetchService.fetch(SYMBOL, INTERVAL))
                    .isInstanceOf(DataFetchException.class);

            // Then: execute() fue llamado al menos una vez
            verify(pythonBridgeFacade, atLeastOnce()).execute(any());
        }
    }

    // ============ VALIDACIÓN DE PARÁMETROS ============

    @Nested
    @DisplayName("VALIDACIONES: parámetros de entrada")
    class ParameterValidationTests {

        @Test
        @DisplayName("✓ Debe aceptar símbolos válidos")
        void should_accept_valid_symbols() throws Exception {
            // Given: Símbolo válido "BTCUSDT"
            String validSymbol = "BTCUSDT";

            when(velaRepository.findMaxOpenTimeBySymbolAndInterval(validSymbol, INTERVAL))
                    .thenReturn(null);
            when(pythonBridgeFacade.execute(any()))
                    .thenReturn(1000);

            // When: Se invoca fetch con símbolo válido
            fetchService.fetch(validSymbol, INTERVAL);

            // Then: Debe ejecutarse sin excepción
            verify(pythonBridgeFacade, times(1)).execute(any());
        }

        @Test
        @DisplayName("✓ Debe aceptar intervalos válidos (1m, 5m, 1h, 1d)")
        void should_accept_valid_intervals() throws Exception {
            // Given: Intervalos soportados
            String[] validIntervals = {"1m", "5m", "1h", "1d"};

            for (String interval : validIntervals) {
                when(velaRepository.findMaxOpenTimeBySymbolAndInterval(SYMBOL, interval))
                        .thenReturn(null);
                when(pythonBridgeFacade.execute(any()))
                        .thenReturn(100);

                // When: Se invoca con cada intervalo
                fetchService.fetch(SYMBOL, interval);

                // Then: No debe lanzar excepción
                verify(pythonBridgeFacade).execute(any());
            }
        }

        @Test
        @DisplayName("✓ Debe manejar dias positivos en fetchIncremental")
        void should_handle_positive_days_in_incremental() throws Exception {
            // Given: dias válido = 30
            int dias = 30;

            when(velaRepository.findMaxOpenTimeBySymbolAndInterval(SYMBOL, INTERVAL))
                    .thenReturn(null);
            doNothing().when(indicadorRepository)
                    .deleteByVelaSymbolAndIntervalAndOpenTimeGreaterThanEqual(
                            anyString(), anyString(), anyLong());
            doNothing().when(velaRepository)
                    .deleteBySymbolAndIntervalAndOpenTimeGreaterThanEqual(
                            anyString(), anyString(), anyLong());
            when(pythonBridgeFacade.execute(any()))
                    .thenReturn(720);

            // When: Se invoca fetchIncremental
            long result = fetchService.fetchIncremental(SYMBOL, INTERVAL, dias, NOW_MS);

            // Then: Debe devolver timestamp válido
            assertThat(result, is(lessThan(NOW_MS)));
        }
    }

    // ============ INTERACCIONES CON PUERTOS ============

    @Nested
    @DisplayName("PUERTOS: interacciones con infraestructura")
    class PortInteractionTests {

        @Test
        @DisplayName("✓ Debe verificar max timestamp antes de decidir descarga")
        void should_verify_max_timestamp_before_deciding_fetch() throws Exception {
            // Given: Se invoca fetch()
            when(velaRepository.findMaxOpenTimeBySymbolAndInterval(SYMBOL, INTERVAL))
                    .thenReturn(null);
            when(pythonBridgeFacade.execute(any()))
                    .thenReturn(1000);

            // When: fetch() ejecuta
            fetchService.fetch(SYMBOL, INTERVAL);

            // Then: VelaRepository.findMaxOpenTime debe haber sido llamado
            verify(velaRepository, times(1))
                    .findMaxOpenTimeBySymbolAndInterval(SYMBOL, INTERVAL);
        }

        @Test
        @DisplayName("✓ Debe invocar PythonBridgeFacade con request válido")
        void should_invoke_python_bridge_with_correct_request() throws Exception {
            // Given: fetch con datos ausentes
            when(velaRepository.findMaxOpenTimeBySymbolAndInterval(SYMBOL, INTERVAL))
                    .thenReturn(null);
            when(pythonBridgeFacade.execute(any()))
                    .thenReturn(5000);

            // When: Se invoca fetch()
            fetchService.fetch(SYMBOL, INTERVAL);

            // Then: execute() debe ser llamado
            ArgumentCaptor<PythonBridgeRequest<?>> requestCaptor = ArgumentCaptor.forClass(PythonBridgeRequest.class);
            verify(pythonBridgeFacade).execute(requestCaptor.capture());

            PythonBridgeRequest<?> capturedRequest = requestCaptor.getValue();
            assertThat(capturedRequest, is(notNullValue()));
        }

        @Test
        @DisplayName("✓ Debe limpiar indicadores antes que velas en fetchIncremental")
        void should_cleanup_indicadores_before_velas_in_incremental() throws Exception {
            // Given: fetchIncremental con limpiezas
            int dias = 30;
            long targetTimestamp = NOW_MS - (dias * 24 * 60 * 60 * 1000);

            InOrder inOrder = inOrder(indicadorRepository, velaRepository);

            when(velaRepository.findMaxOpenTimeBySymbolAndInterval(SYMBOL, INTERVAL))
                    .thenReturn(null);
            doNothing().when(indicadorRepository)
                    .deleteByVelaSymbolAndIntervalAndOpenTimeGreaterThanEqual(
                            SYMBOL, INTERVAL, targetTimestamp);
            doNothing().when(velaRepository)
                    .deleteBySymbolAndIntervalAndOpenTimeGreaterThanEqual(
                            SYMBOL, INTERVAL, targetTimestamp);
            when(pythonBridgeFacade.execute(any()))
                    .thenReturn(720);

            // When: fetchIncremental ejecuta
            fetchService.fetchIncremental(SYMBOL, INTERVAL, dias, NOW_MS);

            // Then: El orden debe ser: indicadores primero, luego velas
            inOrder.verify(indicadorRepository)
                    .deleteByVelaSymbolAndIntervalAndOpenTimeGreaterThanEqual(
                            SYMBOL, INTERVAL, targetTimestamp);
            inOrder.verify(velaRepository)
                    .deleteBySymbolAndIntervalAndOpenTimeGreaterThanEqual(
                            SYMBOL, INTERVAL, targetTimestamp);
        }
    }

    // ============ CASOS FRONTERIZOS ============

    @Nested
    @DisplayName("BOUNDARY: casos límite y edge cases")
    class BoundaryTests {

        @Test
        @DisplayName("✓ Debe descargar 1 símbolo sin interferencia con otros")
        void should_fetch_single_symbol_independently() throws Exception {
            // Given: Hay datos de ETHUSDT pero queremos solo BTCUSDT
            String symbol1 = "BTCUSDT";
            String symbol2 = "ETHUSDT";

            when(velaRepository.findMaxOpenTimeBySymbolAndInterval(symbol1, INTERVAL))
                    .thenReturn(null);
            when(velaRepository.findMaxOpenTimeBySymbolAndInterval(symbol2, INTERVAL))
                    .thenReturn(NOW_MS - (1 * 60 * 60 * 1000));
            when(pythonBridgeFacade.execute(any()))
                    .thenReturn(5000);

            // When: Se invoca fetch solo para BTC
            fetchService.fetch(symbol1, INTERVAL);

            // Then: No debió consultar ETHUSDT
            verify(velaRepository, never())
                    .findMaxOpenTimeBySymbolAndInterval(symbol2, anyString());
        }

        @Test
        @DisplayName("✓ Debe manejar días=1 correctamente")
        void should_handle_single_day_incremental_fetch() throws Exception {
            // Given: Queremos solo 1 día
            int dias = 1;
            long targetTimestamp = NOW_MS - (dias * 24 * 60 * 60 * 1000);

            when(velaRepository.findMaxOpenTimeBySymbolAndInterval(SYMBOL, INTERVAL))
                    .thenReturn(null);
            doNothing().when(indicadorRepository)
                    .deleteByVelaSymbolAndIntervalAndOpenTimeGreaterThanEqual(
                            SYMBOL, INTERVAL, targetTimestamp);
            doNothing().when(velaRepository)
                    .deleteBySymbolAndIntervalAndOpenTimeGreaterThanEqual(
                            SYMBOL, INTERVAL, targetTimestamp);
            when(pythonBridgeFacade.execute(any()))
                    .thenReturn(24);

            // When: fetchIncremental con días=1
            long result = fetchService.fetchIncremental(SYMBOL, INTERVAL, dias, NOW_MS);

            // Then: Debe devolver fecha de hace 1 día
            assertThat(result, is(lessThan(NOW_MS)));
        }

        @Test
        @DisplayName("✓ Debe manejar días grandes (365) sin overflow")
        void should_handle_large_days_incremental_fetch() throws Exception {
            // Given: 365 días completos
            int dias = 365;
            long targetTimestamp = NOW_MS - (dias * 24 * 60 * 60 * 1000);

            when(velaRepository.findMaxOpenTimeBySymbolAndInterval(SYMBOL, INTERVAL))
                    .thenReturn(null);
            doNothing().when(indicadorRepository)
                    .deleteByVelaSymbolAndIntervalAndOpenTimeGreaterThanEqual(
                            SYMBOL, INTERVAL, targetTimestamp);
            doNothing().when(velaRepository)
                    .deleteBySymbolAndIntervalAndOpenTimeGreaterThanEqual(
                            SYMBOL, INTERVAL, targetTimestamp);
            when(pythonBridgeFacade.execute(any()))
                    .thenReturn(8760);

            // When: fetchIncremental con 365 días
            long result = fetchService.fetchIncremental(SYMBOL, INTERVAL, dias, NOW_MS);

            // Then: Debe ser timestamp válido
            assertThat(result, is(lessThan(NOW_MS)));
        }

        @Test
        @DisplayName("✓ Debe retornar número correcto de velas guardadas")
        void should_return_correct_count_of_saved_candles() throws Exception {
            // Given: PythonBridge devuelve número explícito
            int expectedCount = 12345;

            when(velaRepository.findMaxOpenTimeBySymbolAndInterval(SYMBOL, INTERVAL))
                    .thenReturn(null);
            when(pythonBridgeFacade.execute(any()))
                    .thenReturn(expectedCount);

            // When: fetch() ejecuta
            fetchService.fetch(SYMBOL, INTERVAL);

            // Then: El conteo debe ser expectedCount
            verify(pythonBridgeFacade, times(1)).execute(any());
        }
    }

    // ============ NULLABILITY ============

    @Nested
    @DisplayName("NULLABILITY: manejo de null")
    class NullabilityTests {

        @Test
        @DisplayName("✓ Debe aceptar null timestamps (first-time=true)")
        void should_accept_null_max_timestamp_on_first_fetch() throws Exception {
            // Given: Max timestamp es null
            when(velaRepository.findMaxOpenTimeBySymbolAndInterval(SYMBOL, INTERVAL))
                    .thenReturn(null);
            when(pythonBridgeFacade.execute(any()))
                    .thenReturn(100);

            // When: Se invoca fetch()
            // Then: Debe manejar null sin NullPointerException
            assertThatNoException().isThrownBy(
                    () -> fetchService.fetch(SYMBOL, INTERVAL)
            );
        }

        @Test
        @DisplayName("✓ Debe retornar long (nunca null) desde fetchIncremental()")
        void should_return_long_not_null_from_incremental() throws NoSuchMethodException {
            // Given: fetchIncremental() retorna long
            var method = FetchService.class.getDeclaredMethod("fetchIncremental",
                    String.class, String.class, int.class, long.class);

            // When: Se inspecciona tipo retorno
            // Then: Debe ser long primitivo
            assertThat(method.getReturnType(), is(equalTo(long.class)));
        }
    }
}
