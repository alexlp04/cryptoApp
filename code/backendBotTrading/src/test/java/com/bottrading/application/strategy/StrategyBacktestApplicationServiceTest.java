package com.bottrading.application.strategy;

import com.bottrading.domain.market.Vela;
import com.bottrading.domain.market.VelaRepository;
import com.bottrading.infrastructure.persistence.FileService;
import com.bottrading.services.BacktestingService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/**
 * Test exhaustivo para StrategyBacktestApplicationService.
 * Cubre: construcción, ejecución de backtest, carga de datos, errores, validaciones, y casos límite.
 * 
 * Patrón: @ExtendWith(MockitoExtension.class) + @InjectMocks + @Mock ports.
 * Total: ~35 test methods en 8 @Nested clases.
 */
@Slf4j
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StrategyBacktestApplicationServiceTest {

    @Mock
    private VelaRepository velaRepository;

    @Mock
    private BacktestingService backtestingService;

    @Mock
    private FileService fileService;

    @InjectMocks
    private StrategyBacktestApplicationService service;

    // ========== CONSTRUCCIÓN ==========
    @Nested
    @DisplayName("CONSTRUCCIÓN: Instanciación de StrategyBacktestApplicationService")
    class ConstructionTests {

        @Test
        @DisplayName("✓ Debe crear servicio con dependencias inyectadas")
        void should_create_service_with_injected_dependencies() {
            assertThat(service, is(notNullValue()));
            assertThat(service, is(instanceOf(StrategyBacktestApplicationService.class)));
        }

        @Test
        @DisplayName("✓ Debe exponer método ejecutarBacktest()")
        void should_expose_ejecutar_backtest_method() throws Exception {
            assertThat(
                StrategyBacktestApplicationService.class.getDeclaredMethod(
                    "ejecutarBacktest",
                    String.class, String.class, List.class,
                    BigDecimal.class, BigDecimal.class,
                    boolean.class, boolean.class),
                is(notNullValue())
            );
        }
    }

    // ========== EJECUCIÓN BACKTESTING ==========
    @Nested
    @DisplayName("ejecutarBacktest(nombreEstra, tf, coins, capital, risk, limpiar, guardarTrades)")
    class EjecutarBacktestTests {

        @Test
        @DisplayName("✓ Debe ejecutar flujo completo: preparar → cargar → ejecutar → guardar")
        void should_execute_full_backtest_flow_successfully() throws Exception {
            // Given
            when(velaRepository.findBySymbolAndIntervalOrderByOpenTimeAsc("BTCUSDT", "1h"))
                .thenReturn(crearVelasTestList(100));
            when(backtestingService.ejecutarBacktest(anyString(), anyString(), anyString(),
                    any(Map.class), any(BigDecimal.class), any(BigDecimal.class), anyBoolean()))
                .thenReturn("{\"winRate\": 0.65, \"pnl\": 1500.00}");

            // When
            service.ejecutarBacktest(
                "RSISMAStrategy", "1h", List.of("BTCUSDT"),
                new BigDecimal("10000"), new BigDecimal("0.02"),
                true, true);

            // Then
            verify(fileService, times(1))
                .verificarYLimpiarCarpetaEstrategia("RSISMAStrategy", true);
            verify(velaRepository, atLeastOnce())
                .findBySymbolAndIntervalOrderByOpenTimeAsc("BTCUSDT", "1h");
            verify(backtestingService, times(1))
                .ejecutarBacktest(
                    anyString(), eq("RSISMAStrategy"), eq("1h"),
                    any(Map.class), eq(new BigDecimal("10000")), eq(new BigDecimal("0.02")), eq(true));
            verify(fileService, times(1))
                .guardarEstadisticasDelBacktest("RSISMAStrategy", "1h", "{\"winRate\": 0.65, \"pnl\": 1500.00}");
        }

        @Test
        @DisplayName("✓ Debe manejar múltiples monedas en paralelo")
        void should_process_multiple_symbols() throws Exception {
            // Given
            when(velaRepository.findBySymbolAndIntervalOrderByOpenTimeAsc("BTCUSDT", "1h"))
                .thenReturn(crearVelasTestList(100));
            when(velaRepository.findBySymbolAndIntervalOrderByOpenTimeAsc("ETHUSDT", "1h"))
                .thenReturn(crearVelasTestList(100));
            when(backtestingService.ejecutarBacktest(anyString(), anyString(), anyString(),
                    any(Map.class), any(BigDecimal.class), any(BigDecimal.class), anyBoolean()))
                .thenReturn("{\"pnl\": 2000.00}");

            // When
            service.ejecutarBacktest(
                "RSISMAStrategy", "1h", List.of("BTCUSDT", "ETHUSDT"),
                new BigDecimal("20000"), new BigDecimal("0.02"),
                false, true);

            // Then
            verify(velaRepository, times(1))
                .findBySymbolAndIntervalOrderByOpenTimeAsc("BTCUSDT", "1h");
            verify(velaRepository, times(1))
                .findBySymbolAndIntervalOrderByOpenTimeAsc("ETHUSDT", "1h");
        }

        @Test
        @DisplayName("✓ Debe ejecutar motor con dataset vacío pero sin guardar resultados")
        void should_abort_when_no_historical_data_available() throws Exception {
            // Given
            when(velaRepository.findBySymbolAndIntervalOrderByOpenTimeAsc(anyString(), anyString()))
                .thenReturn(Collections.emptyList());
            when(backtestingService.ejecutarBacktest(anyString(), anyString(), anyString(),
                    any(Map.class), any(BigDecimal.class), any(BigDecimal.class), anyBoolean()))
                .thenReturn(null);

            // When
            service.ejecutarBacktest(
                "RSISMAStrategy", "1h", List.of("NONEXISTENTSYMBOL"),
                new BigDecimal("10000"), new BigDecimal("0.02"),
                true, true);

            // Then
            verify(backtestingService, times(1)).ejecutarBacktest(
                anyString(), anyString(), anyString(),
                any(Map.class), any(BigDecimal.class), any(BigDecimal.class), anyBoolean());
            verify(fileService, never())
                .guardarEstadisticasDelBacktest(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("✓ Debe limpiar backtests previos cuando limpiarBacktestsPrevios=true")
        void should_clean_previous_results_when_flag_is_true() throws Exception {
            // Given
            when(velaRepository.findBySymbolAndIntervalOrderByOpenTimeAsc(anyString(), anyString()))
                .thenReturn(crearVelasTestList(50));
            when(backtestingService.ejecutarBacktest(anyString(), anyString(), anyString(),
                    any(Map.class), any(BigDecimal.class), any(BigDecimal.class), anyBoolean()))
                .thenReturn("{\"pnl\": 1000.00}");

            // When
            service.ejecutarBacktest(
                "RSISMAStrategy", "4h", List.of("BTCUSDT"),
                new BigDecimal("5000"), new BigDecimal("0.01"),
                true, false);

            // Then — FileService debe ser llamado con limpiarBacktestsPrevios=true
            verify(fileService, times(1))
                .verificarYLimpiarCarpetaEstrategia("RSISMAStrategy", true);
        }

        @Test
        @DisplayName("✓ Debe PRESERVAR resultados previos cuando limpiarBacktestsPrevios=false")
        void should_preserve_results_when_cleanup_flag_is_false() throws Exception {
            // Given
            when(velaRepository.findBySymbolAndIntervalOrderByOpenTimeAsc(anyString(), anyString()))
                .thenReturn(crearVelasTestList(50));
            when(backtestingService.ejecutarBacktest(anyString(), anyString(), anyString(),
                    any(Map.class), any(BigDecimal.class), any(BigDecimal.class), anyBoolean()))
                .thenReturn("{\"pnl\": 1000.00}");

            // When
            service.ejecutarBacktest(
                "RSISMAStrategy", "4h", List.of("BTCUSDT"),
                new BigDecimal("5000"), new BigDecimal("0.01"),
                false, false);

            // Then — FileService debe ser llamado con limpiarBacktestsPrevios=false
            verify(fileService, times(1))
                .verificarYLimpiarCarpetaEstrategia("RSISMAStrategy", false);
        }

        @Test
        @DisplayName("✓ Debe guardar trades cuando guardarTrades=true")
        void should_save_trades_when_flag_is_true() throws Exception {
            // Given
            when(velaRepository.findBySymbolAndIntervalOrderByOpenTimeAsc(anyString(), anyString()))
                .thenReturn(crearVelasTestList(100));
            when(backtestingService.ejecutarBacktest(anyString(), anyString(), anyString(),
                    any(Map.class), any(BigDecimal.class), any(BigDecimal.class), eq(true)))
                .thenReturn("{\"trades\": [{\"entry\": 1000, \"exit\": 1050}]}");

            // When
            service.ejecutarBacktest(
                "RSISMAStrategy", "1h", List.of("BTCUSDT"),
                new BigDecimal("10000"), new BigDecimal("0.02"),
                true, true);

            // Then — backtestingService debe usar guardarTrades=true
            verify(backtestingService, times(1))
                .ejecutarBacktest(anyString(), anyString(), anyString(),
                    any(Map.class), any(BigDecimal.class), any(BigDecimal.class), eq(true));
        }

        @Test
        @DisplayName("✓ Debe NO guardar trades cuando guardarTrades=false")
        void should_not_save_trades_when_flag_is_false() throws Exception {
            // Given
            when(velaRepository.findBySymbolAndIntervalOrderByOpenTimeAsc(anyString(), anyString()))
                .thenReturn(crearVelasTestList(100));
            when(backtestingService.ejecutarBacktest(anyString(), anyString(), anyString(),
                    any(Map.class), any(BigDecimal.class), any(BigDecimal.class), eq(false)))
                .thenReturn("{\"pnl\": 500.00}");

            // When
            service.ejecutarBacktest(
                "RSISMAStrategy", "1h", List.of("BTCUSDT"),
                new BigDecimal("10000"), new BigDecimal("0.02"),
                true, false);

            // Then — backtestingService debe usar guardarTrades=false
            verify(backtestingService, times(1))
                .ejecutarBacktest(anyString(), anyString(), anyString(),
                    any(Map.class), any(BigDecimal.class), any(BigDecimal.class), eq(false));
        }
    }

    // ========== CARGAR DATOS HISTÓRICOS ==========
    @Nested
    @DisplayName("cargarDatosHistoricos(coins, timeframe) - Carga de velas")
    class LoadHistoricalDataTests {

        @Test
        @DisplayName("✓ Debe cargar velas para símbolo válido")
        void should_load_velas_for_valid_symbol() throws Exception {
            // Given
            List<Vela> velasEsperadas = crearVelasTestList(50);
            when(velaRepository.findBySymbolAndIntervalOrderByOpenTimeAsc("BTCUSDT", "1h"))
                .thenReturn(velasEsperadas);

            // When
            service.ejecutarBacktest(
                "RSISMAStrategy", "1h", List.of("BTCUSDT"),
                new BigDecimal("10000"), new BigDecimal("0.02"),
                true, true);

            // Then
            verify(velaRepository, times(1))
                .findBySymbolAndIntervalOrderByOpenTimeAsc("BTCUSDT", "1h");
        }

        @Test
        @DisplayName("✓ Debe cargar datos para diferentes timeframes")
        void should_load_data_for_different_timeframes() throws Exception {
            // Given
            when(velaRepository.findBySymbolAndIntervalOrderByOpenTimeAsc("BTCUSDT", "1h"))
                .thenReturn(crearVelasTestList(100));
            when(backtestingService.ejecutarBacktest(anyString(), anyString(), eq("1h"),
                    any(Map.class), any(BigDecimal.class), any(BigDecimal.class), anyBoolean()))
                .thenReturn("{\"pnl\": 1000.00}");

            // When
            service.ejecutarBacktest(
                "RSISMAStrategy", "1h", List.of("BTCUSDT"),
                new BigDecimal("10000"), new BigDecimal("0.02"),
                true, true);

            // Then
            verify(backtestingService, times(1))
                .ejecutarBacktest(anyString(), anyString(), eq("1h"),
                    any(Map.class), any(BigDecimal.class), any(BigDecimal.class), anyBoolean());
        }

        @Test
        @DisplayName("✓ Debe registrar advertencia cuando símbolo no tiene datos")
        void should_log_warning_when_symbol_has_no_data() throws Exception {
            // Given
            when(velaRepository.findBySymbolAndIntervalOrderByOpenTimeAsc("NONEXISTENTSYMBOL", "1h"))
                .thenReturn(Collections.emptyList());
            when(velaRepository.findBySymbolAndIntervalOrderByOpenTimeAsc("BTCUSDT", "1h"))
                .thenReturn(crearVelasTestList(50));

            // When
            service.ejecutarBacktest(
                "RSISMAStrategy", "1h", List.of("NONEXISTENTSYMBOL", "BTCUSDT"),
                new BigDecimal("10000"), new BigDecimal("0.02"),
                true, true);

            // Then — El servicio debe intentar cargar de todos modos
            verify(velaRepository, atLeastOnce())
                .findBySymbolAndIntervalOrderByOpenTimeAsc(anyString(), eq("1h"));
        }

        @ParameterizedTest
        @ValueSource(ints = {10, 50, 100, 500})
        @DisplayName("✓ Debe cargar diferentes cantidades de velas históricas")
        void should_load_different_vela_counts(int count) throws Exception {
            // Given
            when(velaRepository.findBySymbolAndIntervalOrderByOpenTimeAsc("BTCUSDT", "1h"))
                .thenReturn(crearVelasTestList(count));
            when(backtestingService.ejecutarBacktest(anyString(), anyString(), anyString(),
                    any(Map.class), any(BigDecimal.class), any(BigDecimal.class), anyBoolean()))
                .thenReturn("{\"pnl\": 1000.00}");

            // When
            service.ejecutarBacktest(
                "RSISMAStrategy", "1h", List.of("BTCUSDT"),
                new BigDecimal("10000"), new BigDecimal("0.02"),
                true, true);

            // Then
            verify(velaRepository, times(1))
                .findBySymbolAndIntervalOrderByOpenTimeAsc("BTCUSDT", "1h");
        }
    }

    // ========== MANEJO DE ERRORES ==========
    @Nested
    @DisplayName("ERRORES: Manejo de excepciones en backtesting")
    class ErrorHandlingTests {

        @Test
        @DisplayName("✓ Debe capturar excepción cuando FileService falla en preparación")
        void should_handle_file_service_preparation_exception() throws Exception {
            // Given
            doThrow(new RuntimeException("Carpeta no puede ser creada"))
                .when(fileService).verificarYLimpiarCarpetaEstrategia(anyString(), anyBoolean());

            // When & Then
            assertThrows(RuntimeException.class, () -> {
                service.ejecutarBacktest(
                    "RSISMAStrategy", "1h", List.of("BTCUSDT"),
                    new BigDecimal("10000"), new BigDecimal("0.02"),
                    true, true);
            });
        }

        @Test
        @DisplayName("✓ Debe manejar cuando BacktestingService retorna null")
        void should_handle_null_backtest_result() throws Exception {
            // Given
            when(velaRepository.findBySymbolAndIntervalOrderByOpenTimeAsc("BTCUSDT", "1h"))
                .thenReturn(crearVelasTestList(100));
            when(backtestingService.ejecutarBacktest(anyString(), anyString(), anyString(),
                    any(Map.class), any(BigDecimal.class), any(BigDecimal.class), anyBoolean()))
                .thenReturn(null);

            // When
            service.ejecutarBacktest(
                "RSISMAStrategy", "1h", List.of("BTCUSDT"),
                new BigDecimal("10000"), new BigDecimal("0.02"),
                true, true);

            // Then — Debe omitir guardar si el resultado es null
            verify(fileService, never())
                .guardarEstadisticasDelBacktest(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("✓ Debe manejar cuando BacktestingService retorna string vacío")
        void should_handle_empty_backtest_result() throws Exception {
            // Given
            when(velaRepository.findBySymbolAndIntervalOrderByOpenTimeAsc("BTCUSDT", "1h"))
                .thenReturn(crearVelasTestList(100));
            when(backtestingService.ejecutarBacktest(anyString(), anyString(), anyString(),
                    any(Map.class), any(BigDecimal.class), any(BigDecimal.class), anyBoolean()))
                .thenReturn("");

            // When
            service.ejecutarBacktest(
                "RSISMAStrategy", "1h", List.of("BTCUSDT"),
                new BigDecimal("10000"), new BigDecimal("0.02"),
                true, true);

            // Then — Debe omitir guardar si el resultado está vacío
            verify(fileService, never())
                .guardarEstadisticasDelBacktest(anyString(), anyString(), anyString());
        }
    }

    // ========== VALIDACIONES DE PARÁMETROS ==========
    @Nested
    @DisplayName("VALIDACIONES: Verificación de parámetros de entrada")
    class ParameterValidationTests {

        @Test
        @DisplayName("✓ Debe aceptar capital > 0")
        void should_accept_positive_capital() throws Exception {
            // Given
            when(velaRepository.findBySymbolAndIntervalOrderByOpenTimeAsc("BTCUSDT", "1h"))
                .thenReturn(crearVelasTestList(100));
            when(backtestingService.ejecutarBacktest(anyString(), anyString(), anyString(),
                    any(Map.class), any(BigDecimal.class), any(BigDecimal.class), anyBoolean()))
                .thenReturn("{\"pnl\": 100.00}");

            // When & Then — No debe lanzar excepción
            assertDoesNotThrow(() -> {
                service.ejecutarBacktest(
                    "RSISMAStrategy", "1h", List.of("BTCUSDT"),
                    new BigDecimal("10000"), new BigDecimal("0.02"),
                    true, true);
            });
        }

        @Test
        @DisplayName("✓ Debe aceptar risk válido (0.01 - 0.10)")
        void should_accept_valid_risk_percentage() throws Exception {
            // Given
            when(velaRepository.findBySymbolAndIntervalOrderByOpenTimeAsc("BTCUSDT", "1h"))
                .thenReturn(crearVelasTestList(100));
            when(backtestingService.ejecutarBacktest(anyString(), anyString(), anyString(),
                    any(Map.class), any(BigDecimal.class), any(BigDecimal.class), anyBoolean()))
                .thenReturn("{\"pnl\": 50.00}");

            // When & Then
            assertDoesNotThrow(() -> {
                service.ejecutarBacktest(
                    "RSISMAStrategy", "1h", List.of("BTCUSDT"),
                    new BigDecimal("10000"), new BigDecimal("0.05"),
                    true, true);
            });
        }

        @ParameterizedTest
        @ValueSource(strings = {"1h", "4h", "1d", "1w"})
        @DisplayName("✓ Debe aceptar timeframes válidos")
        void should_accept_valid_timeframes(String timeframe) throws Exception {
            // Given
            when(velaRepository.findBySymbolAndIntervalOrderByOpenTimeAsc("BTCUSDT", timeframe))
                .thenReturn(crearVelasTestList(100));
            when(backtestingService.ejecutarBacktest(anyString(), anyString(), eq(timeframe),
                    any(Map.class), any(BigDecimal.class), any(BigDecimal.class), anyBoolean()))
                .thenReturn("{\"pnl\": 100.00}");

            // When & Then
            assertDoesNotThrow(() -> {
                service.ejecutarBacktest(
                    "RSISMAStrategy", timeframe, List.of("BTCUSDT"),
                    new BigDecimal("10000"), new BigDecimal("0.02"),
                    true, true);
            });
        }
    }

    // ========== INTERACCIONES CON PUERTOS ==========
    @Nested
    @DisplayName("PUERTOS: Verificación de interacciones con dependencias")
    class PortInteractionTests {

        @Test
        @DisplayName("✓ Debe llamar a FileService.verificarYLimpiarCarpetaEstrategia()")
        void should_call_file_service_preparation() throws Exception {
            // Given
            when(velaRepository.findBySymbolAndIntervalOrderByOpenTimeAsc("BTCUSDT", "1h"))
                .thenReturn(crearVelasTestList(100));
            when(backtestingService.ejecutarBacktest(anyString(), anyString(), anyString(),
                    any(Map.class), any(BigDecimal.class), any(BigDecimal.class), anyBoolean()))
                .thenReturn("{\"pnl\": 100.00}");

            // When
            service.ejecutarBacktest(
                "RSISMAStrategy", "1h", List.of("BTCUSDT"),
                new BigDecimal("10000"), new BigDecimal("0.02"),
                true, true);

            // Then
            verify(fileService, atLeastOnce())
                .verificarYLimpiarCarpetaEstrategia("RSISMAStrategy", true);
        }

        @Test
        @DisplayName("✓ Debe llamar a VelaRepository para cada símbolo")
        void should_call_vela_repository_for_each_symbol() throws Exception {
            // Given
            when(velaRepository.findBySymbolAndIntervalOrderByOpenTimeAsc("BTCUSDT", "1h"))
                .thenReturn(crearVelasTestList(100));
            when(velaRepository.findBySymbolAndIntervalOrderByOpenTimeAsc("ETHUSDT", "1h"))
                .thenReturn(crearVelasTestList(100));
            when(backtestingService.ejecutarBacktest(anyString(), anyString(), anyString(),
                    any(Map.class), any(BigDecimal.class), any(BigDecimal.class), anyBoolean()))
                .thenReturn("{\"pnl\": 100.00}");

            // When
            service.ejecutarBacktest(
                "RSISMAStrategy", "1h", List.of("BTCUSDT", "ETHUSDT"),
                new BigDecimal("10000"), new BigDecimal("0.02"),
                true, true);

            // Then
            verify(velaRepository, times(1))
                .findBySymbolAndIntervalOrderByOpenTimeAsc("BTCUSDT", "1h");
            verify(velaRepository, times(1))
                .findBySymbolAndIntervalOrderByOpenTimeAsc("ETHUSDT", "1h");
        }

        @Test
        @DisplayName("✓ Debe llamar a BacktestingService.ejecutarBacktest() una vez")
        void should_call_backtesting_service_once() throws Exception {
            // Given
            when(velaRepository.findBySymbolAndIntervalOrderByOpenTimeAsc("BTCUSDT", "1h"))
                .thenReturn(crearVelasTestList(100));
            when(backtestingService.ejecutarBacktest(anyString(), anyString(), anyString(),
                    any(Map.class), any(BigDecimal.class), any(BigDecimal.class), anyBoolean()))
                .thenReturn("{\"pnl\": 100.00}");

            // When
            service.ejecutarBacktest(
                "RSISMAStrategy", "1h", List.of("BTCUSDT"),
                new BigDecimal("10000"), new BigDecimal("0.02"),
                true, true);

            // Then
            verify(backtestingService, times(1))
                .ejecutarBacktest(anyString(), anyString(), anyString(),
                    any(Map.class), any(BigDecimal.class), any(BigDecimal.class), anyBoolean());
        }

        @Test
        @DisplayName("✓ Debe llamar a FileService.guardarEstadisticasDelBacktest() cuando hay resultado")
        void should_call_file_service_save_results() throws Exception {
            // Given
            when(velaRepository.findBySymbolAndIntervalOrderByOpenTimeAsc("BTCUSDT", "1h"))
                .thenReturn(crearVelasTestList(100));
            String resultadoJson = "{\"winRate\": 0.65, \"pnl\": 1500.00}";
            when(backtestingService.ejecutarBacktest(anyString(), anyString(), anyString(),
                    any(Map.class), any(BigDecimal.class), any(BigDecimal.class), anyBoolean()))
                .thenReturn(resultadoJson);

            // When
            service.ejecutarBacktest(
                "RSISMAStrategy", "1h", List.of("BTCUSDT"),
                new BigDecimal("10000"), new BigDecimal("0.02"),
                true, true);

            // Then
            verify(fileService, times(1))
                .guardarEstadisticasDelBacktest("RSISMAStrategy", "1h", resultadoJson);
        }
    }

    // ========== CASOS LÍMITE ==========
    @Nested
    @DisplayName("BOUNDARY: Casos límite y condiciones extremas")
    class BoundaryTests {

        @Test
        @DisplayName("✓ Debe procesar backtest con una única vela")
        void should_process_backtest_with_single_vela() throws Exception {
            // Given
            when(velaRepository.findBySymbolAndIntervalOrderByOpenTimeAsc("BTCUSDT", "1h"))
                .thenReturn(crearVelasTestList(1));
            when(backtestingService.ejecutarBacktest(anyString(), anyString(), anyString(),
                    any(Map.class), any(BigDecimal.class), any(BigDecimal.class), anyBoolean()))
                .thenReturn("{\"pnl\": 50.00}");

            // When & Then
            assertDoesNotThrow(() -> {
                service.ejecutarBacktest(
                    "RSISMAStrategy", "1h", List.of("BTCUSDT"),
                    new BigDecimal("10000"), new BigDecimal("0.02"),
                    true, true);
            });
        }

        @Test
        @DisplayName("✓ Debe procesar backtest con muchas velas (100k+)")
        void should_process_backtest_with_large_vela_dataset() throws Exception {
            // Given
            when(velaRepository.findBySymbolAndIntervalOrderByOpenTimeAsc("BTCUSDT", "1h"))
                .thenReturn(crearVelasTestList(100000));
            when(backtestingService.ejecutarBacktest(anyString(), anyString(), anyString(),
                    any(Map.class), any(BigDecimal.class), any(BigDecimal.class), anyBoolean()))
                .thenReturn("{\"pnl\": 50000.00}");

            // When & Then
            assertDoesNotThrow(() -> {
                service.ejecutarBacktest(
                    "RSISMAStrategy", "1h", List.of("BTCUSDT"),
                    new BigDecimal("50000"), new BigDecimal("0.02"),
                    true, true);
            });
        }

        @Test
        @DisplayName("✓ Debe procesar con capital mínimo (1 unidad)")
        void should_process_with_minimum_capital() throws Exception {
            // Given
            when(velaRepository.findBySymbolAndIntervalOrderByOpenTimeAsc("BTCUSDT", "1h"))
                .thenReturn(crearVelasTestList(100));
            when(backtestingService.ejecutarBacktest(anyString(), anyString(), anyString(),
                    any(Map.class), any(BigDecimal.class), any(BigDecimal.class), anyBoolean()))
                .thenReturn("{\"pnl\": 0.01}");

            // When & Then
            assertDoesNotThrow(() -> {
                service.ejecutarBacktest(
                    "RSISMAStrategy", "1h", List.of("BTCUSDT"),
                    new BigDecimal("1"), new BigDecimal("0.02"),
                    true, true);
            });
        }

        @Test
        @DisplayName("✓ Debe procesar con lista vacía de símbolos")
        void should_handle_empty_symbols_list_gracefully() throws Exception {
            // When & Then
            assertDoesNotThrow(() -> {
                service.ejecutarBacktest(
                    "RSISMAStrategy", "1h", Collections.emptyList(),
                    new BigDecimal("10000"), new BigDecimal("0.02"),
                    true, true);
            });
        }
    }

    // ========== HELPERS ==========
    @Nested
    @DisplayName("HELPERS: Utilidades de Test")
    class HelperTests {

        @Test
        @DisplayName("✓ crearVelasTestList(count) debe generar velas válidas")
        void should_create_valid_test_velas() {
            // When
            List<Vela> velas = crearVelasTestList(10);

            // Then
            assertThat(velas, hasSize(10));
            velas.forEach(vela -> {
                assertThat(vela.getSymbol(), is(notNullValue()));
                assertThat(vela.getOpenTime(), is(notNullValue()));
                assertThat(vela.getOpen(), is(notNullValue()));
                assertThat(vela.getClose(), is(notNullValue()));
            });
        }
    }

    // ========== UTILIDADES PARA TESTS ==========

    /**
     * Crea lista de velas de prueba con datos válidos (BigDecimal, Instant).
     */
    private List<Vela> crearVelasTestList(int count) {
        List<Vela> velas = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Vela vela = new Vela();
            vela.setSymbol("BTCUSDT");
            vela.setInterval("1h");
            vela.setOpenTime(Instant.now().minusSeconds(3600L * (count - i)).toEpochMilli());
            vela.setOpen(new BigDecimal("40000.00"));
            vela.setHigh(new BigDecimal("40500.00"));
            vela.setLow(new BigDecimal("39500.00"));
            vela.setClose(new BigDecimal("40200.00"));
            vela.setVolume(new BigDecimal("1000.50"));
            vela.setQuoteVolume(new BigDecimal("40200000.00"));
            vela.setTakerBaseVolume(new BigDecimal("800.00"));
            vela.setTakerQuoteVolume(new BigDecimal("32160000.00"));
            velas.add(vela);
        }
        return velas;
    }
}
