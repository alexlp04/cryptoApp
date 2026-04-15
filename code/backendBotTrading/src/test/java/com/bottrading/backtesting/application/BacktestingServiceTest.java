package com.bottrading.backtesting.application;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

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

import com.bottrading.backtesting.application.port.out.BacktestPersistencePort;
import com.bottrading.backtesting.infrastructure.StatsCsvRepository;
import com.bottrading.market.domain.Vela;
import com.bottrading.market.domain.VelaRepository;
import com.bottrading.shared.exceptions.StrategyExecutionException;
import com.bottrading.shared.utils.ConsoleLoader;
import com.bottrading.shared.utils.PathConfig;
import com.bottrading.trading.infrastructure.bridge.PythonBridgeExecutionException;
import com.bottrading.trading.infrastructure.bridge.PythonBridgeFacade;
import com.bottrading.trading.infrastructure.bridge.PythonBridgeRequest;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BacktestingServiceTest {

    @Mock
    private VelaRepository velaRepository;

    @Mock
    private BacktestPersistencePort backtestPersistencePort;

    @Mock
    private StatsCsvRepository statsCsvRepository;

    @Mock
    private PythonBridgeFacade pythonBridgeFacade;

    @InjectMocks
    private BacktestingService backtestingService;

    // ─── Constantes de test ──────────────────────────────────────────────────
    private static final String ESTRATEGIA = "RSISMAStrategy";
    private static final String TIMEFRAME = "1h";
    private static final BigDecimal CAPITAL = BigDecimal.valueOf(1000);
    private static final BigDecimal RISK = BigDecimal.valueOf(0.02);
    private static final String FAKE_STRATEGY_PATH = "/fake/strategies/RSISMAStrategy.py";
    private static final String FAKE_JSON_RESULTADO =
            "{\"stats\":[{\"symbol\":\"BTCUSDT\",\"timeframe\":\"1h\"}]}";

    // ─── Helpers ─────────────────────────────────────────────────────────────
    private Vela crearVelaTest(String symbol) {
        return new Vela(
                symbol, TIMEFRAME, 1704067200000L,
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
            assertThat(backtestingService, is(notNullValue()));
        }

        @Test
        @DisplayName("✓ Debe aceptar todas las dependencias por constructor")
        void should_accept_constructor_with_four_dependencies() {
            BacktestingService sut = new BacktestingService(
                    velaRepository, backtestPersistencePort,
                    statsCsvRepository, pythonBridgeFacade);
            assertThat(sut, is(notNullValue()));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // LIMPIEZA PREVIA
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("ejecutarBacktest() — Limpieza previa de artefactos")
    class LimpiezaPreviaTests {

        @Test
        @DisplayName("✓ Debe llamar limpiarResultadosPrevios cuando la bandera es true")
        void should_call_limpiarResultadosPrevios_when_flag_is_true() throws PythonBridgeExecutionException {
            when(velaRepository.findBySymbolAndIntervalOrderByOpenTimeAsc(anyString(), anyString()))
                    .thenReturn(List.of(crearVelaTest("BTCUSDT")));

            ConsoleLoader consoleMock = mock(ConsoleLoader.class);
            try (MockedStatic<ConsoleLoader> consoleStatic = mockStatic(ConsoleLoader.class);
                 MockedStatic<PathConfig> pathStatic = mockStatic(PathConfig.class)) {

                consoleStatic.when(ConsoleLoader::getInstance).thenReturn(consoleMock);
                pathStatic.when(() -> PathConfig.getValidStrategyPath(ESTRATEGIA))
                        .thenReturn(FAKE_STRATEGY_PATH);
                doReturn(FAKE_JSON_RESULTADO).when(pythonBridgeFacade).execute(any());

                backtestingService.ejecutarBacktest(ESTRATEGIA, TIMEFRAME,
                        List.of("BTCUSDT"), CAPITAL, RISK, true, false);

                verify(backtestPersistencePort, times(1))
                        .limpiarResultadosPrevios(ESTRATEGIA);
            }
        }

        @Test
        @DisplayName("✓ No debe llamar limpiarResultadosPrevios cuando la bandera es false")
        void should_not_call_limpiarResultadosPrevios_when_flag_is_false() throws PythonBridgeExecutionException {
            when(velaRepository.findBySymbolAndIntervalOrderByOpenTimeAsc(anyString(), anyString()))
                    .thenReturn(List.of(crearVelaTest("BTCUSDT")));

            ConsoleLoader consoleMock = mock(ConsoleLoader.class);
            try (MockedStatic<ConsoleLoader> consoleStatic = mockStatic(ConsoleLoader.class);
                 MockedStatic<PathConfig> pathStatic = mockStatic(PathConfig.class)) {

                consoleStatic.when(ConsoleLoader::getInstance).thenReturn(consoleMock);
                pathStatic.when(() -> PathConfig.getValidStrategyPath(ESTRATEGIA))
                        .thenReturn(FAKE_STRATEGY_PATH);
                doReturn(FAKE_JSON_RESULTADO).when(pythonBridgeFacade).execute(any());

                backtestingService.ejecutarBacktest(ESTRATEGIA, TIMEFRAME,
                        List.of("BTCUSDT"), CAPITAL, RISK, false, false);

                verify(backtestPersistencePort, never())
                        .limpiarResultadosPrevios(anyString());
            }
        }

        @Test
        @DisplayName("✓ Debe pasar el nombre de estrategia correcto a limpiarResultadosPrevios")
        void should_pass_correct_strategy_name_to_limpiarResultadosPrevios() throws PythonBridgeExecutionException {
            String otraEstrategia = "ScalpingRSIStrategy";
            when(velaRepository.findBySymbolAndIntervalOrderByOpenTimeAsc(anyString(), anyString()))
                    .thenReturn(List.of(crearVelaTest("BTCUSDT")));

            ConsoleLoader consoleMock = mock(ConsoleLoader.class);
            try (MockedStatic<ConsoleLoader> consoleStatic = mockStatic(ConsoleLoader.class);
                 MockedStatic<PathConfig> pathStatic = mockStatic(PathConfig.class)) {

                consoleStatic.when(ConsoleLoader::getInstance).thenReturn(consoleMock);
                pathStatic.when(() -> PathConfig.getValidStrategyPath(otraEstrategia))
                        .thenReturn(FAKE_STRATEGY_PATH);
                doReturn(FAKE_JSON_RESULTADO).when(pythonBridgeFacade).execute(any());

                backtestingService.ejecutarBacktest(otraEstrategia, TIMEFRAME,
                        List.of("BTCUSDT"), CAPITAL, RISK, true, false);

                verify(backtestPersistencePort).limpiarResultadosPrevios(otraEstrategia);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CARGA DE DATOS HISTÓRICOS
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("ejecutarBacktest() — Carga de datos históricos")
    class CargaDatosHistoricosTests {

        @Test
        @DisplayName("✓ Debe abortar sin invocar Python cuando la lista de coins está vacía")
        void should_abort_without_calling_python_when_coins_is_empty() throws PythonBridgeExecutionException {
            ConsoleLoader consoleMock = mock(ConsoleLoader.class);
            try (MockedStatic<ConsoleLoader> consoleStatic = mockStatic(ConsoleLoader.class)) {
                consoleStatic.when(ConsoleLoader::getInstance).thenReturn(consoleMock);

                backtestingService.ejecutarBacktest(ESTRATEGIA, TIMEFRAME,
                        Collections.emptyList(), CAPITAL, RISK, false, false);

                verify(pythonBridgeFacade, never()).execute(any());
                verifyNoInteractions(statsCsvRepository);
            }
        }

        @Test
        @DisplayName("✓ Debe consultar el repositorio para cada símbolo de la lista")
        void should_query_repository_once_per_coin() throws PythonBridgeExecutionException {
            List<String> coins = List.of("BTCUSDT", "ETHUSDT", "BNBUSDT");
            when(velaRepository.findBySymbolAndIntervalOrderByOpenTimeAsc(anyString(), anyString()))
                    .thenReturn(List.of(crearVelaTest("BTCUSDT")));

            ConsoleLoader consoleMock = mock(ConsoleLoader.class);
            try (MockedStatic<ConsoleLoader> consoleStatic = mockStatic(ConsoleLoader.class);
                 MockedStatic<PathConfig> pathStatic = mockStatic(PathConfig.class)) {

                consoleStatic.when(ConsoleLoader::getInstance).thenReturn(consoleMock);
                pathStatic.when(() -> PathConfig.getValidStrategyPath(ESTRATEGIA))
                        .thenReturn(FAKE_STRATEGY_PATH);
                doReturn(FAKE_JSON_RESULTADO).when(pythonBridgeFacade).execute(any());

                backtestingService.ejecutarBacktest(ESTRATEGIA, TIMEFRAME,
                        coins, CAPITAL, RISK, false, false);

                verify(velaRepository, times(3))
                        .findBySymbolAndIntervalOrderByOpenTimeAsc(anyString(), eq(TIMEFRAME));
            }
        }

        @Test
        @DisplayName("✓ Debe pasar el símbolo y timeframe correctos al repositorio")
        void should_pass_correct_symbol_and_timeframe_to_repository() throws PythonBridgeExecutionException {
            String symbol = "SOLUSDT";
            String tf = "4h";
            when(velaRepository.findBySymbolAndIntervalOrderByOpenTimeAsc(anyString(), anyString()))
                    .thenReturn(List.of(crearVelaTest(symbol)));

            ConsoleLoader consoleMock = mock(ConsoleLoader.class);
            try (MockedStatic<ConsoleLoader> consoleStatic = mockStatic(ConsoleLoader.class);
                 MockedStatic<PathConfig> pathStatic = mockStatic(PathConfig.class)) {

                consoleStatic.when(ConsoleLoader::getInstance).thenReturn(consoleMock);
                pathStatic.when(() -> PathConfig.getValidStrategyPath(ESTRATEGIA))
                        .thenReturn(FAKE_STRATEGY_PATH);
                doReturn(FAKE_JSON_RESULTADO).when(pythonBridgeFacade).execute(any());

                backtestingService.ejecutarBacktest(ESTRATEGIA, tf,
                        List.of(symbol), CAPITAL, RISK, false, false);

                verify(velaRepository)
                        .findBySymbolAndIntervalOrderByOpenTimeAsc(symbol, tf);
            }
        }

        @Test
        @DisplayName("✓ Debe continuar e invocar Python aunque un símbolo no tenga velas")
        void should_invoke_python_even_when_symbol_has_no_velas() throws PythonBridgeExecutionException {
            when(velaRepository.findBySymbolAndIntervalOrderByOpenTimeAsc("BTCUSDT", TIMEFRAME))
                    .thenReturn(Collections.emptyList());

            ConsoleLoader consoleMock = mock(ConsoleLoader.class);
            try (MockedStatic<ConsoleLoader> consoleStatic = mockStatic(ConsoleLoader.class);
                 MockedStatic<PathConfig> pathStatic = mockStatic(PathConfig.class)) {

                consoleStatic.when(ConsoleLoader::getInstance).thenReturn(consoleMock);
                pathStatic.when(() -> PathConfig.getValidStrategyPath(ESTRATEGIA))
                        .thenReturn(FAKE_STRATEGY_PATH);
                doReturn(FAKE_JSON_RESULTADO).when(pythonBridgeFacade).execute(any());

                // El mapa tendrá entrada para BTCUSDT (lista vacía), por lo que no está vacío
                backtestingService.ejecutarBacktest(ESTRATEGIA, TIMEFRAME,
                        List.of("BTCUSDT"), CAPITAL, RISK, false, false);

                verify(pythonBridgeFacade, times(1)).execute(any());
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // INVOCACIÓN PYTHON
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("ejecutarBacktest() — Invocación del motor Python")
    class InvocacionPythonTests {

        @Test
        @DisplayName("✓ Debe llamar a pythonBridgeFacade.execute() exactamente una vez")
        void should_call_pythonBridgeFacade_execute_exactly_once() throws PythonBridgeExecutionException {
            when(velaRepository.findBySymbolAndIntervalOrderByOpenTimeAsc(anyString(), anyString()))
                    .thenReturn(List.of(crearVelaTest("BTCUSDT")));

            ConsoleLoader consoleMock = mock(ConsoleLoader.class);
            try (MockedStatic<ConsoleLoader> consoleStatic = mockStatic(ConsoleLoader.class);
                 MockedStatic<PathConfig> pathStatic = mockStatic(PathConfig.class)) {

                consoleStatic.when(ConsoleLoader::getInstance).thenReturn(consoleMock);
                pathStatic.when(() -> PathConfig.getValidStrategyPath(ESTRATEGIA))
                        .thenReturn(FAKE_STRATEGY_PATH);
                doReturn(FAKE_JSON_RESULTADO).when(pythonBridgeFacade).execute(any());

                backtestingService.ejecutarBacktest(ESTRATEGIA, TIMEFRAME,
                        List.of("BTCUSDT"), CAPITAL, RISK, false, false);

                verify(pythonBridgeFacade, times(1))
                        .execute(any(PythonBridgeRequest.class));
            }
        }

        @Test
        @DisplayName("✓ Debe lanzar StrategyExecutionException cuando el bridge falla")
        void should_throw_strategyExecutionException_when_bridge_fails() throws PythonBridgeExecutionException {
            when(velaRepository.findBySymbolAndIntervalOrderByOpenTimeAsc(anyString(), anyString()))
                    .thenReturn(List.of(crearVelaTest("BTCUSDT")));

            ConsoleLoader consoleMock = mock(ConsoleLoader.class);
            try (MockedStatic<ConsoleLoader> consoleStatic = mockStatic(ConsoleLoader.class);
                 MockedStatic<PathConfig> pathStatic = mockStatic(PathConfig.class)) {

                consoleStatic.when(ConsoleLoader::getInstance).thenReturn(consoleMock);
                pathStatic.when(() -> PathConfig.getValidStrategyPath(ESTRATEGIA))
                        .thenReturn(FAKE_STRATEGY_PATH);
                doThrow(new PythonBridgeExecutionException("Proceso Python terminó con error"))
                        .when(pythonBridgeFacade).execute(any());

                assertThrows(StrategyExecutionException.class, () ->
                        backtestingService.ejecutarBacktest(ESTRATEGIA, TIMEFRAME,
                                List.of("BTCUSDT"), CAPITAL, RISK, false, false));
            }
        }

        @Test
        @DisplayName("✓ Debe lanzar StrategyExecutionException ante RuntimeException inesperada")
        void should_throw_strategyExecutionException_for_unexpected_runtime_error() throws PythonBridgeExecutionException {
            when(velaRepository.findBySymbolAndIntervalOrderByOpenTimeAsc(anyString(), anyString()))
                    .thenReturn(List.of(crearVelaTest("BTCUSDT")));

            ConsoleLoader consoleMock = mock(ConsoleLoader.class);
            try (MockedStatic<ConsoleLoader> consoleStatic = mockStatic(ConsoleLoader.class);
                 MockedStatic<PathConfig> pathStatic = mockStatic(PathConfig.class)) {

                consoleStatic.when(ConsoleLoader::getInstance).thenReturn(consoleMock);
                pathStatic.when(() -> PathConfig.getValidStrategyPath(ESTRATEGIA))
                        .thenReturn(FAKE_STRATEGY_PATH);
                doThrow(new RuntimeException("Error inesperado en el sistema"))
                        .when(pythonBridgeFacade).execute(any());

                assertThrows(StrategyExecutionException.class, () ->
                        backtestingService.ejecutarBacktest(ESTRATEGIA, TIMEFRAME,
                                List.of("BTCUSDT"), CAPITAL, RISK, false, false));
            }
        }

        @Test
        @DisplayName("✓ No debe guardar estadísticas cuando Python retorna null")
        void should_not_save_stats_when_python_returns_null() throws PythonBridgeExecutionException {
            when(velaRepository.findBySymbolAndIntervalOrderByOpenTimeAsc(anyString(), anyString()))
                    .thenReturn(List.of(crearVelaTest("BTCUSDT")));

            ConsoleLoader consoleMock = mock(ConsoleLoader.class);
            try (MockedStatic<ConsoleLoader> consoleStatic = mockStatic(ConsoleLoader.class);
                 MockedStatic<PathConfig> pathStatic = mockStatic(PathConfig.class)) {

                consoleStatic.when(ConsoleLoader::getInstance).thenReturn(consoleMock);
                pathStatic.when(() -> PathConfig.getValidStrategyPath(ESTRATEGIA))
                        .thenReturn(FAKE_STRATEGY_PATH);
                doReturn(null).when(pythonBridgeFacade).execute(any());

                backtestingService.ejecutarBacktest(ESTRATEGIA, TIMEFRAME,
                        List.of("BTCUSDT"), CAPITAL, RISK, false, false);

                verify(statsCsvRepository, never())
                        .guardarEstadisticasDelBacktest(anyString(), anyString(), anyString());
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PERSISTENCIA DE RESULTADOS
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("ejecutarBacktest() — Persistencia de estadísticas")
    class PersistenciaResultadosTests {

        @Test
        @DisplayName("✓ Debe guardar estadísticas cuando Python retorna JSON válido")
        void should_save_stats_when_python_returns_valid_json() throws PythonBridgeExecutionException {
            when(velaRepository.findBySymbolAndIntervalOrderByOpenTimeAsc(anyString(), anyString()))
                    .thenReturn(List.of(crearVelaTest("BTCUSDT")));

            ConsoleLoader consoleMock = mock(ConsoleLoader.class);
            try (MockedStatic<ConsoleLoader> consoleStatic = mockStatic(ConsoleLoader.class);
                 MockedStatic<PathConfig> pathStatic = mockStatic(PathConfig.class)) {

                consoleStatic.when(ConsoleLoader::getInstance).thenReturn(consoleMock);
                pathStatic.when(() -> PathConfig.getValidStrategyPath(ESTRATEGIA))
                        .thenReturn(FAKE_STRATEGY_PATH);
                doReturn(FAKE_JSON_RESULTADO).when(pythonBridgeFacade).execute(any());

                backtestingService.ejecutarBacktest(ESTRATEGIA, TIMEFRAME,
                        List.of("BTCUSDT"), CAPITAL, RISK, false, false);

                verify(statsCsvRepository, times(1))
                        .guardarEstadisticasDelBacktest(ESTRATEGIA, TIMEFRAME, FAKE_JSON_RESULTADO);
            }
        }

        @Test
        @DisplayName("✓ No debe guardar estadísticas cuando Python retorna cadena vacía")
        void should_not_save_stats_when_python_returns_empty_string() throws PythonBridgeExecutionException {
            when(velaRepository.findBySymbolAndIntervalOrderByOpenTimeAsc(anyString(), anyString()))
                    .thenReturn(List.of(crearVelaTest("BTCUSDT")));

            ConsoleLoader consoleMock = mock(ConsoleLoader.class);
            try (MockedStatic<ConsoleLoader> consoleStatic = mockStatic(ConsoleLoader.class);
                 MockedStatic<PathConfig> pathStatic = mockStatic(PathConfig.class)) {

                consoleStatic.when(ConsoleLoader::getInstance).thenReturn(consoleMock);
                pathStatic.when(() -> PathConfig.getValidStrategyPath(ESTRATEGIA))
                        .thenReturn(FAKE_STRATEGY_PATH);
                doReturn("").when(pythonBridgeFacade).execute(any());

                backtestingService.ejecutarBacktest(ESTRATEGIA, TIMEFRAME,
                        List.of("BTCUSDT"), CAPITAL, RISK, false, false);

                verify(statsCsvRepository, never())
                        .guardarEstadisticasDelBacktest(anyString(), anyString(), anyString());
            }
        }

        @Test
        @DisplayName("✓ Debe pasar la estrategia y timeframe correctos al repositorio de stats")
        void should_pass_correct_strategy_and_timeframe_to_stats_repository() throws PythonBridgeExecutionException {
            String estrategia = "ToggleStrategy";
            String tf = "4h";
            when(velaRepository.findBySymbolAndIntervalOrderByOpenTimeAsc(anyString(), anyString()))
                    .thenReturn(List.of(crearVelaTest("ETHUSDT")));

            ConsoleLoader consoleMock = mock(ConsoleLoader.class);
            try (MockedStatic<ConsoleLoader> consoleStatic = mockStatic(ConsoleLoader.class);
                 MockedStatic<PathConfig> pathStatic = mockStatic(PathConfig.class)) {

                consoleStatic.when(ConsoleLoader::getInstance).thenReturn(consoleMock);
                pathStatic.when(() -> PathConfig.getValidStrategyPath(estrategia))
                        .thenReturn(FAKE_STRATEGY_PATH);
                doReturn(FAKE_JSON_RESULTADO).when(pythonBridgeFacade).execute(any());

                backtestingService.ejecutarBacktest(estrategia, tf,
                        List.of("ETHUSDT"), CAPITAL, RISK, false, false);

                verify(statsCsvRepository)
                        .guardarEstadisticasDelBacktest(estrategia, tf, FAKE_JSON_RESULTADO);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PORTFOLIO MULTI-COIN
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("ejecutarBacktest() — Portfolio multi-coin")
    class PortfolioMultiCoinTests {

        @Test
        @DisplayName("✓ Debe cargar velas de todos los símbolos del portfolio")
        void should_load_velas_for_all_portfolio_coins() throws PythonBridgeExecutionException {
            when(velaRepository.findBySymbolAndIntervalOrderByOpenTimeAsc("BTCUSDT", TIMEFRAME))
                    .thenReturn(List.of(crearVelaTest("BTCUSDT")));
            when(velaRepository.findBySymbolAndIntervalOrderByOpenTimeAsc("ETHUSDT", TIMEFRAME))
                    .thenReturn(List.of(crearVelaTest("ETHUSDT")));

            ConsoleLoader consoleMock = mock(ConsoleLoader.class);
            try (MockedStatic<ConsoleLoader> consoleStatic = mockStatic(ConsoleLoader.class);
                 MockedStatic<PathConfig> pathStatic = mockStatic(PathConfig.class)) {

                consoleStatic.when(ConsoleLoader::getInstance).thenReturn(consoleMock);
                pathStatic.when(() -> PathConfig.getValidStrategyPath(ESTRATEGIA))
                        .thenReturn(FAKE_STRATEGY_PATH);
                doReturn(FAKE_JSON_RESULTADO).when(pythonBridgeFacade).execute(any());

                backtestingService.ejecutarBacktest(ESTRATEGIA, TIMEFRAME,
                        List.of("BTCUSDT", "ETHUSDT"), CAPITAL, RISK, false, false);

                verify(velaRepository).findBySymbolAndIntervalOrderByOpenTimeAsc("BTCUSDT", TIMEFRAME);
                verify(velaRepository).findBySymbolAndIntervalOrderByOpenTimeAsc("ETHUSDT", TIMEFRAME);
            }
        }

        @Test
        @DisplayName("✓ Debe invocar Python una sola vez aunque el portfolio tenga varias monedas")
        void should_invoke_python_once_regardless_of_portfolio_size() throws PythonBridgeExecutionException {
            List<String> coins = List.of("BTCUSDT", "ETHUSDT", "BNBUSDT");
            when(velaRepository.findBySymbolAndIntervalOrderByOpenTimeAsc(anyString(), anyString()))
                    .thenReturn(List.of(crearVelaTest("BTCUSDT")));

            ConsoleLoader consoleMock = mock(ConsoleLoader.class);
            try (MockedStatic<ConsoleLoader> consoleStatic = mockStatic(ConsoleLoader.class);
                 MockedStatic<PathConfig> pathStatic = mockStatic(PathConfig.class)) {

                consoleStatic.when(ConsoleLoader::getInstance).thenReturn(consoleMock);
                pathStatic.when(() -> PathConfig.getValidStrategyPath(ESTRATEGIA))
                        .thenReturn(FAKE_STRATEGY_PATH);
                doReturn(FAKE_JSON_RESULTADO).when(pythonBridgeFacade).execute(any());

                backtestingService.ejecutarBacktest(ESTRATEGIA, TIMEFRAME,
                        coins, CAPITAL, RISK, false, false);

                verify(pythonBridgeFacade, times(1)).execute(any());
            }
        }

        @Test
        @DisplayName("✓ Debe invocar Python con datos parciales cuando solo algunos símbolos tienen velas")
        void should_invoke_python_with_partial_data_when_some_symbols_have_no_velas() throws PythonBridgeExecutionException {
            when(velaRepository.findBySymbolAndIntervalOrderByOpenTimeAsc("BTCUSDT", TIMEFRAME))
                    .thenReturn(List.of(crearVelaTest("BTCUSDT")));
            when(velaRepository.findBySymbolAndIntervalOrderByOpenTimeAsc("UNKNOWNUSDT", TIMEFRAME))
                    .thenReturn(Collections.emptyList());

            ConsoleLoader consoleMock = mock(ConsoleLoader.class);
            try (MockedStatic<ConsoleLoader> consoleStatic = mockStatic(ConsoleLoader.class);
                 MockedStatic<PathConfig> pathStatic = mockStatic(PathConfig.class)) {

                consoleStatic.when(ConsoleLoader::getInstance).thenReturn(consoleMock);
                pathStatic.when(() -> PathConfig.getValidStrategyPath(ESTRATEGIA))
                        .thenReturn(FAKE_STRATEGY_PATH);
                doReturn(FAKE_JSON_RESULTADO).when(pythonBridgeFacade).execute(any());

                backtestingService.ejecutarBacktest(ESTRATEGIA, TIMEFRAME,
                        List.of("BTCUSDT", "UNKNOWNUSDT"), CAPITAL, RISK, false, false);

                verify(pythonBridgeFacade, times(1)).execute(any());
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PUERTOS DE ENTRADA / CONTRATO DE INTERFAZ
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("Contrato de interfaz ExecuteBacktestUseCase")
    class ContratoPuertoEntradaTests {

        @Test
        @DisplayName("✓ BacktestingService debe implementar ExecuteBacktestUseCase")
        void should_implement_executeBacktestUseCase_interface() {
            assertThat(backtestingService instanceof
                    com.bottrading.backtesting.application.port.in.ExecuteBacktestUseCase,
                    is(true));
        }
    }
}
