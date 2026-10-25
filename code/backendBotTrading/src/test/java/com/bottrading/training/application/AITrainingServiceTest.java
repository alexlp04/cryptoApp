package com.bottrading.training.application;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;

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

import com.bottrading.market.application.port.in.FetchMarketDataUseCase;
import com.bottrading.market.domain.Vela;
import com.bottrading.market.domain.VelaRepository;
import com.bottrading.shared.exceptions.StrategyExecutionException;
import com.bottrading.shared.utils.ConsoleLoader;
import com.bottrading.shared.utils.PathConfig;
import com.bottrading.strategy.infrastructure.StrategyInspector;
import com.bottrading.training.application.port.out.TrainingEnginePort;
import com.bottrading.training.domain.TrainingResult;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AITrainingServiceTest {

    @Mock
    private FetchMarketDataUseCase fetchMarketDataUseCase;

    @Mock
    private VelaRepository velaRepository;

    @Mock
    private TrainingEnginePort trainingEnginePort;

    @InjectMocks
    private AITrainingService trainingService;

    // ─── Constantes de test ──────────────────────────────────────────────────
    private static final String STRATEGY        = "RSISMAStrategy";
    private static final String TIMEFRAME       = "1h";
    private static final String SYMBOL          = "BTCUSDT";
    private static final String MODELO          = "xgboost";
    private static final int    DIAS            = 30;
    private static final String FAKE_STRATEGY_PATH = "/fake/strategies/RSISMAStrategy.py";
    private static final int    FAKE_WARMUP     = 50;
    private static final int    FAKE_CANDLES    = 770; // 24*30 + 50

    // ─── Helpers ─────────────────────────────────────────────────────────────
    private Vela crearVelaTest(String symbol, String interval, long openTime) {
        Vela v = new Vela();
        v.setSymbol(symbol);
        v.setInterval(interval);
        v.setOpenTime(openTime);
        v.setClose(new BigDecimal("42000"));
        v.setOpen(new BigDecimal("41000"));
        v.setHigh(new BigDecimal("43000"));
        v.setLow(new BigDecimal("40000"));
        v.setVolume(new BigDecimal("100"));
        return v;
    }

    private List<Vela> crearVelasTestList(int count) {
        long baseTime = 1704067200000L;
        java.util.List<Vela> velas = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            velas.add(crearVelaTest(SYMBOL, TIMEFRAME, baseTime + (i * 3600_000L)));
        }
        return velas;
    }

    private ConsoleLoader crearLoaderMock() {
        ConsoleLoader loader = mock(ConsoleLoader.class);
        doNothing().when(loader).startSpinner(anyString());
        doNothing().when(loader).updateMessage(anyString());
        doNothing().when(loader).stop(anyString());
        return loader;
    }

    private TrainingResult crearResultadoExitosoTest() {
        return new TrainingResult(
                "/models/xgboost_BTCUSDT_1h.pkl",
                Map.of("accuracy", 0.85, "f1", 0.82),
                Map.of("win_rate", 60.0, "profit_factor", 1.5),
                true,
                null);
    }

    private TrainingResult crearResultadoFallidoTest(String error) {
        return new TrainingResult(null, null, null, false, error);
    }

    // ─── Tests ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("CONSTRUCCIÓN: Instanciación del servicio")
    class ConstructionTests {

        @Test
        @DisplayName("✓ Debe instanciarse correctamente con todas las dependencias")
        void should_instantiate_with_all_dependencies() {
            assertThat(trainingService, is(notNullValue()));
        }

        @Test
        @DisplayName("✓ Debe inyectar dependencias por constructor")
        void should_inject_dependencies_by_constructor() {
            AITrainingService service = new AITrainingService(
                    fetchMarketDataUseCase, velaRepository, trainingEnginePort);
            assertThat(service, is(notNullValue()));
        }
    }

    @Nested
    @DisplayName("entrenarModelo() — Validación de parámetros")
    class ValidacionParametrosTests {

        @Test
        @DisplayName("✓ Debe lanzar StrategyExecutionException cuando strategyName es null")
        void should_throw_when_strategyName_is_null() {
            ConsoleLoader loader = crearLoaderMock();
            try (MockedStatic<ConsoleLoader> cs = mockStatic(ConsoleLoader.class)) {
                cs.when(ConsoleLoader::getInstance).thenReturn(loader);

                assertThrows(StrategyExecutionException.class,
                        () -> trainingService.entrenarModelo(MODELO, TIMEFRAME, SYMBOL, DIAS, null, null));
            }
        }

        @Test
        @DisplayName("✓ Debe lanzar StrategyExecutionException cuando strategyName es blank")
        void should_throw_when_strategyName_is_blank() {
            ConsoleLoader loader = crearLoaderMock();
            try (MockedStatic<ConsoleLoader> cs = mockStatic(ConsoleLoader.class)) {
                cs.when(ConsoleLoader::getInstance).thenReturn(loader);

                assertThrows(StrategyExecutionException.class,
                        () -> trainingService.entrenarModelo(MODELO, TIMEFRAME, SYMBOL, DIAS, null, "   "));
            }
        }

        @Test
        @DisplayName("✓ Debe lanzar StrategyExecutionException cuando strategyName es cadena vacía")
        void should_throw_when_strategyName_is_empty_string() {
            ConsoleLoader loader = crearLoaderMock();
            try (MockedStatic<ConsoleLoader> cs = mockStatic(ConsoleLoader.class)) {
                cs.when(ConsoleLoader::getInstance).thenReturn(loader);

                assertThrows(StrategyExecutionException.class,
                        () -> trainingService.entrenarModelo(MODELO, TIMEFRAME, SYMBOL, DIAS, null, ""));
            }
        }

        @Test
        @DisplayName("✓ El mensaje de la excepción debe indicar que se requiere --strategy")
        void should_include_strategy_hint_in_exception_message() {
            ConsoleLoader loader = crearLoaderMock();
            try (MockedStatic<ConsoleLoader> cs = mockStatic(ConsoleLoader.class)) {
                cs.when(ConsoleLoader::getInstance).thenReturn(loader);

                StrategyExecutionException ex = assertThrows(StrategyExecutionException.class,
                        () -> trainingService.entrenarModelo(MODELO, TIMEFRAME, SYMBOL, DIAS, null, null));

                assertThat(ex.getMessage(), containsString("--strategy"));
            }
        }

        @Test
        @DisplayName("✓ No debe consultar el repositorio si la estrategia es null")
        void should_not_query_repository_when_strategy_is_null() {
            ConsoleLoader loader = crearLoaderMock();
            try (MockedStatic<ConsoleLoader> cs = mockStatic(ConsoleLoader.class)) {
                cs.when(ConsoleLoader::getInstance).thenReturn(loader);

                assertThrows(StrategyExecutionException.class,
                        () -> trainingService.entrenarModelo(MODELO, TIMEFRAME, SYMBOL, DIAS, null, null));

                verify(velaRepository, never())
                        .findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(
                                anyString(), anyString(), anyLong());
            }
        }
    }

    @Nested
    @DisplayName("entrenarModelo() — Inspección de estrategia Python")
    class InspeccionEstrategiaTests {

        @Test
        @DisplayName("✓ Debe lanzar StrategyExecutionException cuando StrategyInspector falla")
        void should_throw_when_strategy_inspector_fails() {
            ConsoleLoader loader = crearLoaderMock();
            try (MockedStatic<ConsoleLoader> cs = mockStatic(ConsoleLoader.class);
                 MockedStatic<StrategyInspector> si = mockStatic(StrategyInspector.class)) {

                cs.when(ConsoleLoader::getInstance).thenReturn(loader);
                si.when(() -> StrategyInspector.getCandlesRequired(STRATEGY, TIMEFRAME, DIAS))
                        .thenThrow(new RuntimeException("Python process failed"));

                assertThrows(StrategyExecutionException.class,
                        () -> trainingService.entrenarModelo(MODELO, TIMEFRAME, SYMBOL, DIAS, null, STRATEGY));
            }
        }

        @Test
        @DisplayName("✓ El mensaje debe contener el nombre de la estrategia cuando la inspección falla")
        void should_include_strategy_name_when_inspector_fails() {
            ConsoleLoader loader = crearLoaderMock();
            try (MockedStatic<ConsoleLoader> cs = mockStatic(ConsoleLoader.class);
                 MockedStatic<StrategyInspector> si = mockStatic(StrategyInspector.class)) {

                cs.when(ConsoleLoader::getInstance).thenReturn(loader);
                si.when(() -> StrategyInspector.getCandlesRequired(STRATEGY, TIMEFRAME, DIAS))
                        .thenThrow(new RuntimeException("timeout"));

                StrategyExecutionException ex = assertThrows(StrategyExecutionException.class,
                        () -> trainingService.entrenarModelo(MODELO, TIMEFRAME, SYMBOL, DIAS, null, STRATEGY));

                assertThat(ex.getMessage(), containsString(STRATEGY));
            }
        }

        @Test
        @DisplayName("✓ No debe invocar el engine si la inspección de estrategia falla")
        void should_not_invoke_engine_when_inspector_fails() {
            ConsoleLoader loader = crearLoaderMock();
            try (MockedStatic<ConsoleLoader> cs = mockStatic(ConsoleLoader.class);
                 MockedStatic<StrategyInspector> si = mockStatic(StrategyInspector.class)) {

                cs.when(ConsoleLoader::getInstance).thenReturn(loader);
                si.when(() -> StrategyInspector.getCandlesRequired(STRATEGY, TIMEFRAME, DIAS))
                        .thenThrow(new RuntimeException("error"));

                assertThrows(StrategyExecutionException.class,
                        () -> trainingService.entrenarModelo(MODELO, TIMEFRAME, SYMBOL, DIAS, null, STRATEGY));

                verify(trainingEnginePort, never())
                        .ejecutarEntrenamiento(anyString(), anyMap(), anyString(), anyMap(), anyString(), anyInt());
            }
        }
    }

    @Nested
    @DisplayName("entrenarModelo() — Consulta de datos del mercado")
    class ConsultaDatosTests {

        @Test
        @DisplayName("✓ Debe retornar mensaje de error cuando no hay velas disponibles")
        void should_return_error_string_when_no_velas() {
            ConsoleLoader loader = crearLoaderMock();
            try (MockedStatic<ConsoleLoader> cs = mockStatic(ConsoleLoader.class);
                 MockedStatic<StrategyInspector> si = mockStatic(StrategyInspector.class);
                 MockedStatic<PathConfig> pc = mockStatic(PathConfig.class)) {

                cs.when(ConsoleLoader::getInstance).thenReturn(loader);
                si.when(() -> StrategyInspector.getCandlesRequired(STRATEGY, TIMEFRAME, DIAS))
                        .thenReturn(FAKE_CANDLES);
                pc.when(() -> PathConfig.getValidStrategyPath(STRATEGY))
                        .thenReturn(FAKE_STRATEGY_PATH);

                when(fetchMarketDataUseCase.fetchIncremental(anyString(), anyString(), anyInt(), anyLong()))
                                .thenReturn(0L);
                when(velaRepository.findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(
                        anyString(), anyString(), anyLong()))
                        .thenReturn(Collections.emptyList());

                String resultado = trainingService.entrenarModelo(
                        MODELO, TIMEFRAME, SYMBOL, DIAS, null, STRATEGY);

                assertThat(resultado, startsWith("Error:"));
            }
        }

        @Test
        @DisplayName("✓ El mensaje de error debe mencionar el símbolo cuando no hay velas")
        void should_include_symbol_in_error_when_no_velas() {
            ConsoleLoader loader = crearLoaderMock();
            try (MockedStatic<ConsoleLoader> cs = mockStatic(ConsoleLoader.class);
                 MockedStatic<StrategyInspector> si = mockStatic(StrategyInspector.class);
                 MockedStatic<PathConfig> pc = mockStatic(PathConfig.class)) {

                cs.when(ConsoleLoader::getInstance).thenReturn(loader);
                si.when(() -> StrategyInspector.getCandlesRequired(STRATEGY, TIMEFRAME, DIAS))
                        .thenReturn(FAKE_CANDLES);
                pc.when(() -> PathConfig.getValidStrategyPath(STRATEGY))
                        .thenReturn(FAKE_STRATEGY_PATH);

                when(fetchMarketDataUseCase.fetchIncremental(anyString(), anyString(), anyInt(), anyLong()))
                                .thenReturn(0L);
                when(velaRepository.findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(
                        anyString(), anyString(), anyLong()))
                        .thenReturn(Collections.emptyList());

                String resultado = trainingService.entrenarModelo(
                        MODELO, TIMEFRAME, SYMBOL, DIAS, null, STRATEGY);

                assertThat(resultado, containsString(SYMBOL));
            }
        }

        @Test
        @DisplayName("✓ No debe lanzar excepción cuando no hay velas — retorna String de error")
        void should_not_throw_when_no_velas_but_return_error_string() {
            ConsoleLoader loader = crearLoaderMock();
            try (MockedStatic<ConsoleLoader> cs = mockStatic(ConsoleLoader.class);
                 MockedStatic<StrategyInspector> si = mockStatic(StrategyInspector.class);
                 MockedStatic<PathConfig> pc = mockStatic(PathConfig.class)) {

                cs.when(ConsoleLoader::getInstance).thenReturn(loader);
                si.when(() -> StrategyInspector.getCandlesRequired(STRATEGY, TIMEFRAME, DIAS))
                        .thenReturn(FAKE_CANDLES);
                pc.when(() -> PathConfig.getValidStrategyPath(STRATEGY))
                        .thenReturn(FAKE_STRATEGY_PATH);

                when(fetchMarketDataUseCase.fetchIncremental(anyString(), anyString(), anyInt(), anyLong()))
                                .thenReturn(0L);
                when(velaRepository.findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(
                        anyString(), anyString(), anyLong()))
                        .thenReturn(Collections.emptyList());

                String resultado = trainingService.entrenarModelo(
                        MODELO, TIMEFRAME, SYMBOL, DIAS, null, STRATEGY);

                assertThat(resultado, is(notNullValue()));
            }
        }

        @Test
        @DisplayName("✓ No debe invocar el engine si no hay velas disponibles")
        void should_not_invoke_engine_when_no_velas() {
            ConsoleLoader loader = crearLoaderMock();
            try (MockedStatic<ConsoleLoader> cs = mockStatic(ConsoleLoader.class);
                 MockedStatic<StrategyInspector> si = mockStatic(StrategyInspector.class);
                 MockedStatic<PathConfig> pc = mockStatic(PathConfig.class)) {

                cs.when(ConsoleLoader::getInstance).thenReturn(loader);
                si.when(() -> StrategyInspector.getCandlesRequired(STRATEGY, TIMEFRAME, DIAS))
                        .thenReturn(FAKE_CANDLES);
                pc.when(() -> PathConfig.getValidStrategyPath(STRATEGY))
                        .thenReturn(FAKE_STRATEGY_PATH);

                when(fetchMarketDataUseCase.fetchIncremental(anyString(), anyString(), anyInt(), anyLong()))
                                .thenReturn(0L);
                when(velaRepository.findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(
                        anyString(), anyString(), anyLong()))
                        .thenReturn(Collections.emptyList());

                trainingService.entrenarModelo(MODELO, TIMEFRAME, SYMBOL, DIAS, null, STRATEGY);

                verify(trainingEnginePort, never())
                        .ejecutarEntrenamiento(anyString(), anyMap(), anyString(), anyMap(), anyString(), anyInt());
            }
        }

        @Test
        @DisplayName("✓ Debe invocar fetchIncremental con los parámetros correctos")
        void should_invoke_fetch_with_correct_params() {
            ConsoleLoader loader = crearLoaderMock();
            try (MockedStatic<ConsoleLoader> cs = mockStatic(ConsoleLoader.class);
                 MockedStatic<StrategyInspector> si = mockStatic(StrategyInspector.class);
                 MockedStatic<PathConfig> pc = mockStatic(PathConfig.class)) {

                cs.when(ConsoleLoader::getInstance).thenReturn(loader);
                si.when(() -> StrategyInspector.getCandlesRequired(STRATEGY, TIMEFRAME, DIAS))
                        .thenReturn(FAKE_CANDLES);
                pc.when(() -> PathConfig.getValidStrategyPath(STRATEGY))
                        .thenReturn(FAKE_STRATEGY_PATH);

                when(fetchMarketDataUseCase.fetchIncremental(anyString(), anyString(), anyInt(), anyLong()))
                                .thenReturn(0L);
                when(velaRepository.findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(
                        anyString(), anyString(), anyLong()))
                        .thenReturn(Collections.emptyList());

                trainingService.entrenarModelo(MODELO, TIMEFRAME, SYMBOL, DIAS, null, STRATEGY);

                verify(fetchMarketDataUseCase)
                        .fetchIncremental(eq(SYMBOL), eq(TIMEFRAME), anyInt(), anyLong());
            }
        }
    }

    @Nested
    @DisplayName("entrenarModelo() — Motor de entrenamiento")
    class MotorEntrenamientoTests {

        @Test
        @DisplayName("✓ Debe lanzar StrategyExecutionException cuando el motor falla")
        void should_throw_when_engine_returns_failure() {
            ConsoleLoader loader = crearLoaderMock();
            try (MockedStatic<ConsoleLoader> cs = mockStatic(ConsoleLoader.class);
                 MockedStatic<StrategyInspector> si = mockStatic(StrategyInspector.class);
                 MockedStatic<PathConfig> pc = mockStatic(PathConfig.class)) {

                cs.when(ConsoleLoader::getInstance).thenReturn(loader);
                si.when(() -> StrategyInspector.getCandlesRequired(STRATEGY, TIMEFRAME, DIAS))
                        .thenReturn(FAKE_CANDLES);
                pc.when(() -> PathConfig.getValidStrategyPath(STRATEGY))
                        .thenReturn(FAKE_STRATEGY_PATH);

                when(fetchMarketDataUseCase.fetchIncremental(anyString(), anyString(), anyInt(), anyLong()))
                                .thenReturn(0L);
                when(velaRepository.findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(
                        anyString(), anyString(), anyLong()))
                        .thenReturn(crearVelasTestList(100));
                when(trainingEnginePort.ejecutarEntrenamiento(
                        anyString(), anyMap(), anyString(), anyMap(), anyString(), anyInt()))
                        .thenReturn(crearResultadoFallidoTest("modelo no converge"));

                assertThrows(StrategyExecutionException.class,
                        () -> trainingService.entrenarModelo(MODELO, TIMEFRAME, SYMBOL, DIAS, null, STRATEGY));
            }
        }

        @Test
        @DisplayName("✓ El mensaje de la excepción debe incluir el error del motor")
        void should_include_engine_error_in_exception_message() {
            String errorEsperado = "modelo no converge";
            ConsoleLoader loader = crearLoaderMock();
            try (MockedStatic<ConsoleLoader> cs = mockStatic(ConsoleLoader.class);
                 MockedStatic<StrategyInspector> si = mockStatic(StrategyInspector.class);
                 MockedStatic<PathConfig> pc = mockStatic(PathConfig.class)) {

                cs.when(ConsoleLoader::getInstance).thenReturn(loader);
                si.when(() -> StrategyInspector.getCandlesRequired(STRATEGY, TIMEFRAME, DIAS))
                        .thenReturn(FAKE_CANDLES);
                pc.when(() -> PathConfig.getValidStrategyPath(STRATEGY))
                        .thenReturn(FAKE_STRATEGY_PATH);

                when(fetchMarketDataUseCase.fetchIncremental(anyString(), anyString(), anyInt(), anyLong()))
                                .thenReturn(0L);
                when(velaRepository.findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(
                        anyString(), anyString(), anyLong()))
                        .thenReturn(crearVelasTestList(100));
                when(trainingEnginePort.ejecutarEntrenamiento(
                        anyString(), anyMap(), anyString(), anyMap(), anyString(), anyInt()))
                        .thenReturn(crearResultadoFallidoTest(errorEsperado));

                StrategyExecutionException ex = assertThrows(StrategyExecutionException.class,
                        () -> trainingService.entrenarModelo(MODELO, TIMEFRAME, SYMBOL, DIAS, null, STRATEGY));

                assertThat(ex.getMessage(), containsString(errorEsperado));
            }
        }

        @Test
        @DisplayName("✓ Debe pasar el strategyPath correcto al engine")
        void should_pass_correct_strategy_path_to_engine() {
            ConsoleLoader loader = crearLoaderMock();
            try (MockedStatic<ConsoleLoader> cs = mockStatic(ConsoleLoader.class);
                 MockedStatic<StrategyInspector> si = mockStatic(StrategyInspector.class);
                 MockedStatic<PathConfig> pc = mockStatic(PathConfig.class)) {

                cs.when(ConsoleLoader::getInstance).thenReturn(loader);
                si.when(() -> StrategyInspector.getCandlesRequired(STRATEGY, TIMEFRAME, DIAS))
                        .thenReturn(FAKE_CANDLES);
                pc.when(() -> PathConfig.getValidStrategyPath(STRATEGY))
                        .thenReturn(FAKE_STRATEGY_PATH);

                when(fetchMarketDataUseCase.fetchIncremental(anyString(), anyString(), anyInt(), anyLong()))
                                .thenReturn(0L);
                when(velaRepository.findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(
                        anyString(), anyString(), anyLong()))
                        .thenReturn(crearVelasTestList(100));
                when(trainingEnginePort.ejecutarEntrenamiento(
                        anyString(), anyMap(), anyString(), anyMap(), anyString(), anyInt()))
                        .thenReturn(crearResultadoExitosoTest());

                trainingService.entrenarModelo(MODELO, TIMEFRAME, SYMBOL, DIAS, null, STRATEGY);

                verify(trainingEnginePort).ejecutarEntrenamiento(
                        eq(MODELO), anyMap(), eq(FAKE_STRATEGY_PATH), anyMap(), eq(TIMEFRAME), anyInt());
            }
        }

        @Test
        @DisplayName("✓ Debe propagar warmup_candles al engine (si no, entrena con indicadores a medio formar)")
        void should_forward_warmup_candles_to_engine() {
            ConsoleLoader loader = crearLoaderMock();
            try (MockedStatic<ConsoleLoader> cs = mockStatic(ConsoleLoader.class);
                 MockedStatic<StrategyInspector> si = mockStatic(StrategyInspector.class);
                 MockedStatic<PathConfig> pc = mockStatic(PathConfig.class)) {

                cs.when(ConsoleLoader::getInstance).thenReturn(loader);
                si.when(() -> StrategyInspector.getWarmupPeriod(STRATEGY))
                        .thenReturn(FAKE_WARMUP);
                si.when(() -> StrategyInspector.getCandlesRequired(TIMEFRAME, DIAS, FAKE_WARMUP))
                        .thenReturn(FAKE_CANDLES);
                pc.when(() -> PathConfig.getValidStrategyPath(STRATEGY))
                        .thenReturn(FAKE_STRATEGY_PATH);

                when(fetchMarketDataUseCase.fetchIncremental(anyString(), anyString(), anyInt(), anyLong()))
                        .thenReturn(0L);
                when(velaRepository.findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(
                        anyString(), anyString(), anyLong()))
                        .thenReturn(crearVelasTestList(100));
                when(trainingEnginePort.ejecutarEntrenamiento(
                        anyString(), anyMap(), anyString(), anyMap(), anyString(), anyInt()))
                        .thenReturn(crearResultadoExitosoTest());

                trainingService.entrenarModelo(MODELO, TIMEFRAME, SYMBOL, DIAS, null, STRATEGY);

                verify(trainingEnginePort).ejecutarEntrenamiento(
                        eq(MODELO), anyMap(), eq(FAKE_STRATEGY_PATH), anyMap(), eq(TIMEFRAME), eq(FAKE_WARMUP));
            }
        }

        @Test
        @DisplayName("✓ Debe pasar Map vacío como hyperparams cuando se recibe null")
        void should_pass_empty_map_when_hyperparams_is_null() {
            ConsoleLoader loader = crearLoaderMock();
            try (MockedStatic<ConsoleLoader> cs = mockStatic(ConsoleLoader.class);
                 MockedStatic<StrategyInspector> si = mockStatic(StrategyInspector.class);
                 MockedStatic<PathConfig> pc = mockStatic(PathConfig.class)) {

                cs.when(ConsoleLoader::getInstance).thenReturn(loader);
                si.when(() -> StrategyInspector.getCandlesRequired(STRATEGY, TIMEFRAME, DIAS))
                        .thenReturn(FAKE_CANDLES);
                pc.when(() -> PathConfig.getValidStrategyPath(STRATEGY))
                        .thenReturn(FAKE_STRATEGY_PATH);

                when(fetchMarketDataUseCase.fetchIncremental(anyString(), anyString(), anyInt(), anyLong()))
                                .thenReturn(0L);
                when(velaRepository.findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(
                        anyString(), anyString(), anyLong()))
                        .thenReturn(crearVelasTestList(100));
                when(trainingEnginePort.ejecutarEntrenamiento(
                        anyString(), anyMap(), anyString(), anyMap(), anyString(), anyInt()))
                        .thenReturn(crearResultadoExitosoTest());

                trainingService.entrenarModelo(MODELO, TIMEFRAME, SYMBOL, DIAS, null, STRATEGY);

                verify(trainingEnginePort).ejecutarEntrenamiento(
                        anyString(), eq(Map.of()), anyString(), anyMap(), anyString(), anyInt());
            }
        }
    }

    @Nested
    @DisplayName("entrenarModelo() — Flujo completo (happy path)")
    class FlujoCOMPLETOTests {

        @Test
        @DisplayName("✓ Debe retornar JSON con status=success en flujo exitoso")
        void should_return_json_with_success_status() {
            ConsoleLoader loader = crearLoaderMock();
            try (MockedStatic<ConsoleLoader> cs = mockStatic(ConsoleLoader.class);
                 MockedStatic<StrategyInspector> si = mockStatic(StrategyInspector.class);
                 MockedStatic<PathConfig> pc = mockStatic(PathConfig.class)) {

                cs.when(ConsoleLoader::getInstance).thenReturn(loader);
                si.when(() -> StrategyInspector.getCandlesRequired(STRATEGY, TIMEFRAME, DIAS))
                        .thenReturn(FAKE_CANDLES);
                pc.when(() -> PathConfig.getValidStrategyPath(STRATEGY))
                        .thenReturn(FAKE_STRATEGY_PATH);

                when(fetchMarketDataUseCase.fetchIncremental(anyString(), anyString(), anyInt(), anyLong()))
                                .thenReturn(0L);
                when(velaRepository.findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(
                        anyString(), anyString(), anyLong()))
                        .thenReturn(crearVelasTestList(100));
                when(trainingEnginePort.ejecutarEntrenamiento(
                        anyString(), anyMap(), anyString(), anyMap(), anyString(), anyInt()))
                        .thenReturn(crearResultadoExitosoTest());

                String resultado = trainingService.entrenarModelo(
                        MODELO, TIMEFRAME, SYMBOL, DIAS, null, STRATEGY);

                assertThat(resultado, containsString("success"));
            }
        }

        @Test
        @DisplayName("✓ El JSON resultado debe contener el symbol")
        void should_include_symbol_in_result_json() {
            ConsoleLoader loader = crearLoaderMock();
            try (MockedStatic<ConsoleLoader> cs = mockStatic(ConsoleLoader.class);
                 MockedStatic<StrategyInspector> si = mockStatic(StrategyInspector.class);
                 MockedStatic<PathConfig> pc = mockStatic(PathConfig.class)) {

                cs.when(ConsoleLoader::getInstance).thenReturn(loader);
                si.when(() -> StrategyInspector.getCandlesRequired(STRATEGY, TIMEFRAME, DIAS))
                        .thenReturn(FAKE_CANDLES);
                pc.when(() -> PathConfig.getValidStrategyPath(STRATEGY))
                        .thenReturn(FAKE_STRATEGY_PATH);

                when(fetchMarketDataUseCase.fetchIncremental(anyString(), anyString(), anyInt(), anyLong()))
                                .thenReturn(0L);
                when(velaRepository.findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(
                        anyString(), anyString(), anyLong()))
                        .thenReturn(crearVelasTestList(50));
                when(trainingEnginePort.ejecutarEntrenamiento(
                        anyString(), anyMap(), anyString(), anyMap(), anyString(), anyInt()))
                        .thenReturn(crearResultadoExitosoTest());

                String resultado = trainingService.entrenarModelo(
                        MODELO, TIMEFRAME, SYMBOL, DIAS, null, STRATEGY);

                assertThat(resultado, containsString(SYMBOL));
            }
        }

        @Test
        @DisplayName("✓ El JSON resultado debe contener el model_path del resultado")
        void should_include_model_path_in_result_json() {
            ConsoleLoader loader = crearLoaderMock();
            try (MockedStatic<ConsoleLoader> cs = mockStatic(ConsoleLoader.class);
                 MockedStatic<StrategyInspector> si = mockStatic(StrategyInspector.class);
                 MockedStatic<PathConfig> pc = mockStatic(PathConfig.class)) {

                cs.when(ConsoleLoader::getInstance).thenReturn(loader);
                si.when(() -> StrategyInspector.getCandlesRequired(STRATEGY, TIMEFRAME, DIAS))
                        .thenReturn(FAKE_CANDLES);
                pc.when(() -> PathConfig.getValidStrategyPath(STRATEGY))
                        .thenReturn(FAKE_STRATEGY_PATH);

                when(fetchMarketDataUseCase.fetchIncremental(anyString(), anyString(), anyInt(), anyLong()))
                                .thenReturn(0L);
                when(velaRepository.findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(
                        anyString(), anyString(), anyLong()))
                        .thenReturn(crearVelasTestList(50));

                TrainingResult result = crearResultadoExitosoTest();
                when(trainingEnginePort.ejecutarEntrenamiento(
                        anyString(), anyMap(), anyString(), anyMap(), anyString(), anyInt()))
                        .thenReturn(result);

                String resultado = trainingService.entrenarModelo(
                        MODELO, TIMEFRAME, SYMBOL, DIAS, null, STRATEGY);

                assertThat(resultado, containsString("model_path"));
            }
        }

        @Test
        @DisplayName("✓ El JSON resultado debe contener el timeframe")
        void should_include_timeframe_in_result_json() {
            ConsoleLoader loader = crearLoaderMock();
            try (MockedStatic<ConsoleLoader> cs = mockStatic(ConsoleLoader.class);
                 MockedStatic<StrategyInspector> si = mockStatic(StrategyInspector.class);
                 MockedStatic<PathConfig> pc = mockStatic(PathConfig.class)) {

                cs.when(ConsoleLoader::getInstance).thenReturn(loader);
                si.when(() -> StrategyInspector.getCandlesRequired(STRATEGY, TIMEFRAME, DIAS))
                        .thenReturn(FAKE_CANDLES);
                pc.when(() -> PathConfig.getValidStrategyPath(STRATEGY))
                        .thenReturn(FAKE_STRATEGY_PATH);

                when(fetchMarketDataUseCase.fetchIncremental(anyString(), anyString(), anyInt(), anyLong()))
                                .thenReturn(0L);
                when(velaRepository.findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(
                        anyString(), anyString(), anyLong()))
                        .thenReturn(crearVelasTestList(50));
                when(trainingEnginePort.ejecutarEntrenamiento(
                        anyString(), anyMap(), anyString(), anyMap(), anyString(), anyInt()))
                        .thenReturn(crearResultadoExitosoTest());

                String resultado = trainingService.entrenarModelo(
                        MODELO, TIMEFRAME, SYMBOL, DIAS, null, STRATEGY);

                assertThat(resultado, containsString(TIMEFRAME));
            }
        }

        @Test
        @DisplayName("✓ Debe invocar el motor de entrenamiento exactamente una vez")
        void should_invoke_engine_exactly_once() {
            ConsoleLoader loader = crearLoaderMock();
            try (MockedStatic<ConsoleLoader> cs = mockStatic(ConsoleLoader.class);
                 MockedStatic<StrategyInspector> si = mockStatic(StrategyInspector.class);
                 MockedStatic<PathConfig> pc = mockStatic(PathConfig.class)) {

                cs.when(ConsoleLoader::getInstance).thenReturn(loader);
                si.when(() -> StrategyInspector.getCandlesRequired(STRATEGY, TIMEFRAME, DIAS))
                        .thenReturn(FAKE_CANDLES);
                pc.when(() -> PathConfig.getValidStrategyPath(STRATEGY))
                        .thenReturn(FAKE_STRATEGY_PATH);

                when(fetchMarketDataUseCase.fetchIncremental(anyString(), anyString(), anyInt(), anyLong()))
                                .thenReturn(0L);
                when(velaRepository.findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(
                        anyString(), anyString(), anyLong()))
                        .thenReturn(crearVelasTestList(100));
                when(trainingEnginePort.ejecutarEntrenamiento(
                        anyString(), anyMap(), anyString(), anyMap(), anyString(), anyInt()))
                        .thenReturn(crearResultadoExitosoTest());

                trainingService.entrenarModelo(MODELO, TIMEFRAME, SYMBOL, DIAS, null, STRATEGY);

                verify(trainingEnginePort)
                        .ejecutarEntrenamiento(anyString(), anyMap(), anyString(), anyMap(), anyString(), anyInt());
            }
        }

        @Test
        @DisplayName("✓ Debe incluir trading_simulation en el JSON si el resultado lo contiene")
        void should_include_trading_simulation_when_present_in_result() {
            ConsoleLoader loader = crearLoaderMock();
            try (MockedStatic<ConsoleLoader> cs = mockStatic(ConsoleLoader.class);
                 MockedStatic<StrategyInspector> si = mockStatic(StrategyInspector.class);
                 MockedStatic<PathConfig> pc = mockStatic(PathConfig.class)) {

                cs.when(ConsoleLoader::getInstance).thenReturn(loader);
                si.when(() -> StrategyInspector.getCandlesRequired(STRATEGY, TIMEFRAME, DIAS))
                        .thenReturn(FAKE_CANDLES);
                pc.when(() -> PathConfig.getValidStrategyPath(STRATEGY))
                        .thenReturn(FAKE_STRATEGY_PATH);

                when(fetchMarketDataUseCase.fetchIncremental(anyString(), anyString(), anyInt(), anyLong()))
                                .thenReturn(0L);
                when(velaRepository.findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(
                        anyString(), anyString(), anyLong()))
                        .thenReturn(crearVelasTestList(50));
                when(trainingEnginePort.ejecutarEntrenamiento(
                        anyString(), anyMap(), anyString(), anyMap(), anyString(), anyInt()))
                        .thenReturn(crearResultadoExitosoTest());

                String resultado = trainingService.entrenarModelo(
                        MODELO, TIMEFRAME, SYMBOL, DIAS, null, STRATEGY);

                assertThat(resultado, containsString("trading_simulation_test"));
            }
        }
    }

    @Nested
    @DisplayName("entrenarModelo() — Manejo de excepciones")
    class ManejoExcepcionesTests {

        @Test
        @DisplayName("✓ Debe relanzar StrategyExecutionException sin envolver")
        void should_rethrow_strategy_execution_exception_directly() {
            ConsoleLoader loader = crearLoaderMock();
            try (MockedStatic<ConsoleLoader> cs = mockStatic(ConsoleLoader.class);
                 MockedStatic<StrategyInspector> si = mockStatic(StrategyInspector.class);
                 MockedStatic<PathConfig> pc = mockStatic(PathConfig.class)) {

                cs.when(ConsoleLoader::getInstance).thenReturn(loader);
                si.when(() -> StrategyInspector.getCandlesRequired(STRATEGY, TIMEFRAME, DIAS))
                        .thenReturn(FAKE_CANDLES);
                pc.when(() -> PathConfig.getValidStrategyPath(STRATEGY))
                        .thenReturn(FAKE_STRATEGY_PATH);

                when(fetchMarketDataUseCase.fetchIncremental(anyString(), anyString(), anyInt(), anyLong()))
                                .thenReturn(0L);
                when(velaRepository.findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(
                        anyString(), anyString(), anyLong()))
                        .thenReturn(crearVelasTestList(100));

                String mensajeOriginal = "fallo controlado en engine";
                when(trainingEnginePort.ejecutarEntrenamiento(
                        anyString(), anyMap(), anyString(), anyMap(), anyString(), anyInt()))
                        .thenThrow(new StrategyExecutionException(mensajeOriginal));

                StrategyExecutionException ex = assertThrows(StrategyExecutionException.class,
                        () -> trainingService.entrenarModelo(MODELO, TIMEFRAME, SYMBOL, DIAS, null, STRATEGY));

                assertThat(ex.getMessage(), containsString(mensajeOriginal));
            }
        }

        @Test
        @DisplayName("✓ Debe envolver excepciones inesperadas en StrategyExecutionException")
        void should_wrap_unexpected_exception_in_strategy_execution_exception() {
            ConsoleLoader loader = crearLoaderMock();
            try (MockedStatic<ConsoleLoader> cs = mockStatic(ConsoleLoader.class);
                 MockedStatic<StrategyInspector> si = mockStatic(StrategyInspector.class);
                 MockedStatic<PathConfig> pc = mockStatic(PathConfig.class)) {

                cs.when(ConsoleLoader::getInstance).thenReturn(loader);
                si.when(() -> StrategyInspector.getCandlesRequired(STRATEGY, TIMEFRAME, DIAS))
                        .thenReturn(FAKE_CANDLES);
                pc.when(() -> PathConfig.getValidStrategyPath(STRATEGY))
                        .thenReturn(FAKE_STRATEGY_PATH);

                when(fetchMarketDataUseCase.fetchIncremental(anyString(), anyString(), anyInt(), anyLong()))
                                .thenReturn(0L);
                when(velaRepository.findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(
                        anyString(), anyString(), anyLong()))
                        .thenThrow(new RuntimeException("error inesperado de base de datos"));

                assertThrows(StrategyExecutionException.class,
                        () -> trainingService.entrenarModelo(MODELO, TIMEFRAME, SYMBOL, DIAS, null, STRATEGY));
            }
        }

        @Test
        @DisplayName("✓ La excepción envolvente debe incluir el mensaje original")
        void should_preserve_original_message_when_wrapping_exception() {
            String mensajeOriginal = "error inesperado de base de datos";
            ConsoleLoader loader = crearLoaderMock();
            try (MockedStatic<ConsoleLoader> cs = mockStatic(ConsoleLoader.class);
                 MockedStatic<StrategyInspector> si = mockStatic(StrategyInspector.class);
                 MockedStatic<PathConfig> pc = mockStatic(PathConfig.class)) {

                cs.when(ConsoleLoader::getInstance).thenReturn(loader);
                si.when(() -> StrategyInspector.getCandlesRequired(STRATEGY, TIMEFRAME, DIAS))
                        .thenReturn(FAKE_CANDLES);
                pc.when(() -> PathConfig.getValidStrategyPath(STRATEGY))
                        .thenReturn(FAKE_STRATEGY_PATH);

                when(fetchMarketDataUseCase.fetchIncremental(anyString(), anyString(), anyInt(), anyLong()))
                                .thenReturn(0L);
                when(velaRepository.findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(
                        anyString(), anyString(), anyLong()))
                        .thenThrow(new RuntimeException(mensajeOriginal));

                StrategyExecutionException ex = assertThrows(StrategyExecutionException.class,
                        () -> trainingService.entrenarModelo(MODELO, TIMEFRAME, SYMBOL, DIAS, null, STRATEGY));

                assertThat(ex.getMessage(), containsString(mensajeOriginal));
            }
        }

        @Test
        @DisplayName("✓ Debe lanzar StrategyExecutionException cuando PathConfig falla")
        void should_throw_when_path_config_fails() {
            ConsoleLoader loader = crearLoaderMock();
            try (MockedStatic<ConsoleLoader> cs = mockStatic(ConsoleLoader.class);
                 MockedStatic<StrategyInspector> si = mockStatic(StrategyInspector.class);
                 MockedStatic<PathConfig> pc = mockStatic(PathConfig.class)) {

                cs.when(ConsoleLoader::getInstance).thenReturn(loader);
                si.when(() -> StrategyInspector.getCandlesRequired(STRATEGY, TIMEFRAME, DIAS))
                        .thenReturn(FAKE_CANDLES);
                pc.when(() -> PathConfig.getValidStrategyPath(STRATEGY))
                        .thenThrow(new IllegalArgumentException("estrategia no encontrada"));

                assertThrows(StrategyExecutionException.class,
                        () -> trainingService.entrenarModelo(MODELO, TIMEFRAME, SYMBOL, DIAS, null, STRATEGY));
            }
        }
    }

    @Nested
    @DisplayName("entrenarModelo() — Hyperparams y parámetros adicionales")
    class HyperparameterTests {

        @Test
        @DisplayName("✓ Debe pasar los hyperparams al engine cuando se proporcionan")
        void should_pass_hyperparams_to_engine() {
            Map<String, Object> hyperparams = Map.of("n_estimators", 100, "max_depth", 5);
            ConsoleLoader loader = crearLoaderMock();
            try (MockedStatic<ConsoleLoader> cs = mockStatic(ConsoleLoader.class);
                 MockedStatic<StrategyInspector> si = mockStatic(StrategyInspector.class);
                 MockedStatic<PathConfig> pc = mockStatic(PathConfig.class)) {

                cs.when(ConsoleLoader::getInstance).thenReturn(loader);
                si.when(() -> StrategyInspector.getCandlesRequired(STRATEGY, TIMEFRAME, DIAS))
                        .thenReturn(FAKE_CANDLES);
                pc.when(() -> PathConfig.getValidStrategyPath(STRATEGY))
                        .thenReturn(FAKE_STRATEGY_PATH);

                when(fetchMarketDataUseCase.fetchIncremental(anyString(), anyString(), anyInt(), anyLong()))
                                .thenReturn(0L);
                when(velaRepository.findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(
                        anyString(), anyString(), anyLong()))
                        .thenReturn(crearVelasTestList(50));
                when(trainingEnginePort.ejecutarEntrenamiento(
                        anyString(), anyMap(), anyString(), anyMap(), anyString(), anyInt()))
                        .thenReturn(crearResultadoExitosoTest());

                trainingService.entrenarModelo(MODELO, TIMEFRAME, SYMBOL, DIAS, hyperparams, STRATEGY);

                verify(trainingEnginePort).ejecutarEntrenamiento(
                        eq(MODELO), eq(hyperparams), anyString(), anyMap(), anyString(), anyInt());
            }
        }

        @Test
        @DisplayName("✓ Debe funcionar correctamente con timeframe 1m")
        void should_work_with_timeframe_1m() {
            ConsoleLoader loader = crearLoaderMock();
            try (MockedStatic<ConsoleLoader> cs = mockStatic(ConsoleLoader.class);
                 MockedStatic<StrategyInspector> si = mockStatic(StrategyInspector.class);
                 MockedStatic<PathConfig> pc = mockStatic(PathConfig.class)) {

                cs.when(ConsoleLoader::getInstance).thenReturn(loader);
                si.when(() -> StrategyInspector.getCandlesRequired(STRATEGY, "1m", DIAS))
                        .thenReturn(1440 * DIAS + FAKE_WARMUP);
                pc.when(() -> PathConfig.getValidStrategyPath(STRATEGY))
                        .thenReturn(FAKE_STRATEGY_PATH);

                when(fetchMarketDataUseCase.fetchIncremental(anyString(), anyString(), anyInt(), anyLong()))
                                .thenReturn(0L);
                when(velaRepository.findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(
                        anyString(), anyString(), anyLong()))
                        .thenReturn(crearVelasTestList(50));
                when(trainingEnginePort.ejecutarEntrenamiento(
                        anyString(), anyMap(), anyString(), anyMap(), anyString(), anyInt()))
                        .thenReturn(crearResultadoExitosoTest());

                String resultado = trainingService.entrenarModelo(
                        MODELO, "1m", SYMBOL, DIAS, null, STRATEGY);

                assertThat(resultado, is(notNullValue()));
            }
        }
    }
}
