package com.bottrading.training.application;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
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
import org.mockito.ArgumentCaptor;
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
import com.bottrading.training.application.port.out.OptimizationEnginePort;
import com.bottrading.training.domain.OptimizationResult;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AIOptimizationServiceTest {

    @Mock
    private FetchMarketDataUseCase fetchMarketDataUseCase;

    @Mock
    private VelaRepository velaRepo;

    @Mock
    private OptimizationEnginePort optimizationEnginePort;

    @InjectMocks
    private AIOptimizationService optimizationService;

    // ─── Constantes de test ──────────────────────────────────────────────────
    private static final String STRATEGY        = "RSISMAStrategy";
    private static final String TIMEFRAME       = "1h";
    private static final String SYMBOL          = "BTCUSDT";
    private static final String MODELO          = "xgboost";
    private static final int    DIAS            = 30;
    private static final double MIN_COMPOSITE   = 80.0;
    private static final int    N_TRIALS        = 100;
    private static final int    CV_FOLDS        = 5;
    private static final String FAKE_STRATEGY_PATH = "/fake/strategies/RSISMAStrategy.py";
    private static final int    FAKE_WARMUP     = 50;
    private static final int    FAKE_CANDLES    = 770;

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

    private OptimizationResult crearResultadoExitosoTest() {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("status", "completed");
        data.put("min_accuracy_reached", true);
        data.put("trials_completed", 50);
        data.put("trials_total", 50);
        data.put("best_composite_score", 0.82);
        data.put("best_f1_cv", 0.80);
        data.put("best_accuracy_cv_pct", 78.5);
        data.put("best_accuracy_train", 0.81);
        data.put("best_win_rate_cv", 0.55);
        data.put("overfit_gap", 0.02);
        data.put("best_params", Map.of("n_estimators", 200));
        data.put("final_metrics", Map.of("accuracy", 79.0, "precision", 80.0, "recall", 78.0, "f1", 79.0));
        return new OptimizationResult(data, true, null);
    }

    private OptimizationResult crearResultadoFallidoTest(String error) {
        return new OptimizationResult(Map.of(), false, error);
    }

    // ─── Tests ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("CONSTRUCCIÓN: Instanciación del servicio")
    class ConstructionTests {

        @Test
        @DisplayName("✓ Debe instanciarse correctamente con todas las dependencias")
        void should_instantiate_with_all_dependencies() {
            assertThat(optimizationService, is(notNullValue()));
        }

        @Test
        @DisplayName("✓ Debe inyectar dependencias por constructor")
        void should_inject_dependencies_by_constructor() {
            AIOptimizationService service = new AIOptimizationService(
                    fetchMarketDataUseCase, velaRepo, optimizationEnginePort);
            assertThat(service, is(notNullValue()));
        }
    }

    @Nested
    @DisplayName("optimizarHiperparametros() — Validación de parámetros")
    class ValidacionParametrosTests {

        @Test
        @DisplayName("✓ Debe lanzar StrategyExecutionException cuando strategyName es null")
        void should_throw_when_strategy_name_is_null() {
            ConsoleLoader loader = crearLoaderMock();
            try (MockedStatic<ConsoleLoader> cs = mockStatic(ConsoleLoader.class)) {
                cs.when(ConsoleLoader::getInstance).thenReturn(loader);

                assertThrows(StrategyExecutionException.class,
                        () -> optimizationService.optimizarHiperparametros(
                                MODELO, TIMEFRAME, SYMBOL, DIAS, null, MIN_COMPOSITE, N_TRIALS, CV_FOLDS));
            }
        }

        @Test
        @DisplayName("✓ Debe lanzar StrategyExecutionException cuando strategyName es blank")
        void should_throw_when_strategy_name_is_blank() {
            ConsoleLoader loader = crearLoaderMock();
            try (MockedStatic<ConsoleLoader> cs = mockStatic(ConsoleLoader.class)) {
                cs.when(ConsoleLoader::getInstance).thenReturn(loader);

                assertThrows(StrategyExecutionException.class,
                        () -> optimizationService.optimizarHiperparametros(
                                MODELO, TIMEFRAME, SYMBOL, DIAS, "   ", MIN_COMPOSITE, N_TRIALS, CV_FOLDS));
            }
        }

        @Test
        @DisplayName("✓ Debe lanzar StrategyExecutionException cuando strategyName es cadena vacía")
        void should_throw_when_strategy_name_is_empty_string() {
            ConsoleLoader loader = crearLoaderMock();
            try (MockedStatic<ConsoleLoader> cs = mockStatic(ConsoleLoader.class)) {
                cs.when(ConsoleLoader::getInstance).thenReturn(loader);

                assertThrows(StrategyExecutionException.class,
                        () -> optimizationService.optimizarHiperparametros(
                                MODELO, TIMEFRAME, SYMBOL, DIAS, "", MIN_COMPOSITE, N_TRIALS, CV_FOLDS));
            }
        }

        @Test
        @DisplayName("✓ El mensaje de la excepción debe indicar que se requiere --strategy")
        void should_include_strategy_hint_in_exception_message() {
            ConsoleLoader loader = crearLoaderMock();
            try (MockedStatic<ConsoleLoader> cs = mockStatic(ConsoleLoader.class)) {
                cs.when(ConsoleLoader::getInstance).thenReturn(loader);

                StrategyExecutionException ex = assertThrows(StrategyExecutionException.class,
                        () -> optimizationService.optimizarHiperparametros(
                                MODELO, TIMEFRAME, SYMBOL, DIAS, null, MIN_COMPOSITE, N_TRIALS, CV_FOLDS));

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
                        () -> optimizationService.optimizarHiperparametros(
                                MODELO, TIMEFRAME, SYMBOL, DIAS, null, MIN_COMPOSITE, N_TRIALS, CV_FOLDS));

                verify(velaRepo, never())
                        .findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(
                                anyString(), anyString(), anyLong());
            }
        }
    }

    @Nested
    @DisplayName("optimizarHiperparametros() — Inspección de estrategia Python")
    class InspeccionEstrategiaTests {

        @Test
        @DisplayName("✓ Debe lanzar StrategyExecutionException cuando getWarmupPeriod falla")
        void should_throw_when_get_warmup_period_fails() {
            ConsoleLoader loader = crearLoaderMock();
            try (MockedStatic<ConsoleLoader> cs = mockStatic(ConsoleLoader.class);
                 MockedStatic<StrategyInspector> si = mockStatic(StrategyInspector.class)) {

                cs.when(ConsoleLoader::getInstance).thenReturn(loader);
                si.when(() -> StrategyInspector.getWarmupPeriod(STRATEGY))
                        .thenThrow(new RuntimeException("Python process timeout"));

                assertThrows(StrategyExecutionException.class,
                        () -> optimizationService.optimizarHiperparametros(
                                MODELO, TIMEFRAME, SYMBOL, DIAS, STRATEGY, MIN_COMPOSITE, N_TRIALS, CV_FOLDS));
            }
        }

        @Test
        @DisplayName("✓ Debe lanzar StrategyExecutionException cuando getCandlesRequired falla")
        void should_throw_when_get_candles_required_fails() {
            ConsoleLoader loader = crearLoaderMock();
            try (MockedStatic<ConsoleLoader> cs = mockStatic(ConsoleLoader.class);
                 MockedStatic<StrategyInspector> si = mockStatic(StrategyInspector.class)) {

                cs.when(ConsoleLoader::getInstance).thenReturn(loader);
                si.when(() -> StrategyInspector.getWarmupPeriod(STRATEGY)).thenReturn(FAKE_WARMUP);
                si.when(() -> StrategyInspector.getCandlesRequired(STRATEGY, TIMEFRAME, DIAS))
                        .thenThrow(new RuntimeException("candles calculation failed"));

                assertThrows(StrategyExecutionException.class,
                        () -> optimizationService.optimizarHiperparametros(
                                MODELO, TIMEFRAME, SYMBOL, DIAS, STRATEGY, MIN_COMPOSITE, N_TRIALS, CV_FOLDS));
            }
        }

        @Test
        @DisplayName("✓ El mensaje de error debe contener el nombre de la estrategia")
        void should_include_strategy_name_when_inspection_fails() {
            ConsoleLoader loader = crearLoaderMock();
            try (MockedStatic<ConsoleLoader> cs = mockStatic(ConsoleLoader.class);
                 MockedStatic<StrategyInspector> si = mockStatic(StrategyInspector.class)) {

                cs.when(ConsoleLoader::getInstance).thenReturn(loader);
                si.when(() -> StrategyInspector.getWarmupPeriod(STRATEGY))
                        .thenThrow(new RuntimeException("error"));

                StrategyExecutionException ex = assertThrows(StrategyExecutionException.class,
                        () -> optimizationService.optimizarHiperparametros(
                                MODELO, TIMEFRAME, SYMBOL, DIAS, STRATEGY, MIN_COMPOSITE, N_TRIALS, CV_FOLDS));

                assertThat(ex.getMessage(), containsString(STRATEGY));
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
                si.when(() -> StrategyInspector.getWarmupPeriod(STRATEGY)).thenReturn(FAKE_WARMUP);
                si.when(() -> StrategyInspector.getCandlesRequired(STRATEGY, TIMEFRAME, DIAS))
                        .thenReturn(FAKE_CANDLES);
                pc.when(() -> PathConfig.getValidStrategyPath(STRATEGY))
                        .thenThrow(new IllegalArgumentException("estrategia no encontrada"));

                assertThrows(StrategyExecutionException.class,
                        () -> optimizationService.optimizarHiperparametros(
                                MODELO, TIMEFRAME, SYMBOL, DIAS, STRATEGY, MIN_COMPOSITE, N_TRIALS, CV_FOLDS));
            }
        }

        @Test
        @DisplayName("✓ No debe invocar el engine si la inspección falla")
        void should_not_invoke_engine_when_inspection_fails() {
            ConsoleLoader loader = crearLoaderMock();
            try (MockedStatic<ConsoleLoader> cs = mockStatic(ConsoleLoader.class);
                 MockedStatic<StrategyInspector> si = mockStatic(StrategyInspector.class)) {

                cs.when(ConsoleLoader::getInstance).thenReturn(loader);
                si.when(() -> StrategyInspector.getWarmupPeriod(STRATEGY))
                        .thenThrow(new RuntimeException("timeout"));

                assertThrows(StrategyExecutionException.class,
                        () -> optimizationService.optimizarHiperparametros(
                                MODELO, TIMEFRAME, SYMBOL, DIAS, STRATEGY, MIN_COMPOSITE, N_TRIALS, CV_FOLDS));

                verify(optimizationEnginePort, never())
                        .ejecutarOptimizacion(anyString(), anyString(), anyMap(),
                                anyString(), any(Double.class), any(), anyInt(), anyInt());
            }
        }
    }

    @Nested
    @DisplayName("optimizarHiperparametros() — Consulta de datos del mercado")
    class ConsultaDatosTests {

        @Test
        @DisplayName("✓ Debe retornar mensaje de error cuando no hay velas disponibles")
        void should_return_error_string_when_no_velas() {
            ConsoleLoader loader = crearLoaderMock();
            try (MockedStatic<ConsoleLoader> cs = mockStatic(ConsoleLoader.class);
                 MockedStatic<StrategyInspector> si = mockStatic(StrategyInspector.class);
                 MockedStatic<PathConfig> pc = mockStatic(PathConfig.class)) {

                cs.when(ConsoleLoader::getInstance).thenReturn(loader);
                si.when(() -> StrategyInspector.getWarmupPeriod(STRATEGY)).thenReturn(FAKE_WARMUP);
                si.when(() -> StrategyInspector.getCandlesRequired(STRATEGY, TIMEFRAME, DIAS))
                        .thenReturn(FAKE_CANDLES);
                pc.when(() -> PathConfig.getValidStrategyPath(STRATEGY))
                        .thenReturn(FAKE_STRATEGY_PATH);

                when(fetchMarketDataUseCase.fetchIncremental(anyString(), anyString(), anyInt(), anyLong()))
                                .thenReturn(0L);
                when(velaRepo.findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(
                        anyString(), anyString(), anyLong()))
                        .thenReturn(Collections.emptyList());

                String resultado = optimizationService.optimizarHiperparametros(
                        MODELO, TIMEFRAME, SYMBOL, DIAS, STRATEGY, MIN_COMPOSITE, N_TRIALS, CV_FOLDS);

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
                si.when(() -> StrategyInspector.getWarmupPeriod(STRATEGY)).thenReturn(FAKE_WARMUP);
                si.when(() -> StrategyInspector.getCandlesRequired(STRATEGY, TIMEFRAME, DIAS))
                        .thenReturn(FAKE_CANDLES);
                pc.when(() -> PathConfig.getValidStrategyPath(STRATEGY))
                        .thenReturn(FAKE_STRATEGY_PATH);

                when(fetchMarketDataUseCase.fetchIncremental(anyString(), anyString(), anyInt(), anyLong()))
                                .thenReturn(0L);
                when(velaRepo.findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(
                        anyString(), anyString(), anyLong()))
                        .thenReturn(Collections.emptyList());

                String resultado = optimizationService.optimizarHiperparametros(
                        MODELO, TIMEFRAME, SYMBOL, DIAS, STRATEGY, MIN_COMPOSITE, N_TRIALS, CV_FOLDS);

                assertThat(resultado, containsString(SYMBOL));
            }
        }

        @Test
        @DisplayName("✓ No debe lanzar excepción cuando no hay velas — retorna String de error")
        void should_not_throw_when_no_velas() {
            ConsoleLoader loader = crearLoaderMock();
            try (MockedStatic<ConsoleLoader> cs = mockStatic(ConsoleLoader.class);
                 MockedStatic<StrategyInspector> si = mockStatic(StrategyInspector.class);
                 MockedStatic<PathConfig> pc = mockStatic(PathConfig.class)) {

                cs.when(ConsoleLoader::getInstance).thenReturn(loader);
                si.when(() -> StrategyInspector.getWarmupPeriod(STRATEGY)).thenReturn(FAKE_WARMUP);
                si.when(() -> StrategyInspector.getCandlesRequired(STRATEGY, TIMEFRAME, DIAS))
                        .thenReturn(FAKE_CANDLES);
                pc.when(() -> PathConfig.getValidStrategyPath(STRATEGY))
                        .thenReturn(FAKE_STRATEGY_PATH);

                when(fetchMarketDataUseCase.fetchIncremental(anyString(), anyString(), anyInt(), anyLong()))
                                .thenReturn(0L);
                when(velaRepo.findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(
                        anyString(), anyString(), anyLong()))
                        .thenReturn(Collections.emptyList());

                String resultado = optimizationService.optimizarHiperparametros(
                        MODELO, TIMEFRAME, SYMBOL, DIAS, STRATEGY, MIN_COMPOSITE, N_TRIALS, CV_FOLDS);

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
                si.when(() -> StrategyInspector.getWarmupPeriod(STRATEGY)).thenReturn(FAKE_WARMUP);
                si.when(() -> StrategyInspector.getCandlesRequired(STRATEGY, TIMEFRAME, DIAS))
                        .thenReturn(FAKE_CANDLES);
                pc.when(() -> PathConfig.getValidStrategyPath(STRATEGY))
                        .thenReturn(FAKE_STRATEGY_PATH);

                when(fetchMarketDataUseCase.fetchIncremental(anyString(), anyString(), anyInt(), anyLong()))
                                .thenReturn(0L);
                when(velaRepo.findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(
                        anyString(), anyString(), anyLong()))
                        .thenReturn(Collections.emptyList());

                optimizationService.optimizarHiperparametros(
                        MODELO, TIMEFRAME, SYMBOL, DIAS, STRATEGY, MIN_COMPOSITE, N_TRIALS, CV_FOLDS);

                verify(optimizationEnginePort, never())
                        .ejecutarOptimizacion(anyString(), anyString(), anyMap(),
                                anyString(), any(Double.class), any(), anyInt(), anyInt());
            }
        }
    }

    @Nested
    @DisplayName("optimizarHiperparametros() — Motor de optimización")
    class MotorOptimizacionTests {

        @Test
        @DisplayName("✓ Debe lanzar StrategyExecutionException cuando el motor devuelve failure")
        void should_throw_when_engine_returns_failure() {
            ConsoleLoader loader = crearLoaderMock();
            try (MockedStatic<ConsoleLoader> cs = mockStatic(ConsoleLoader.class);
                 MockedStatic<StrategyInspector> si = mockStatic(StrategyInspector.class);
                 MockedStatic<PathConfig> pc = mockStatic(PathConfig.class)) {

                cs.when(ConsoleLoader::getInstance).thenReturn(loader);
                si.when(() -> StrategyInspector.getWarmupPeriod(STRATEGY)).thenReturn(FAKE_WARMUP);
                si.when(() -> StrategyInspector.getCandlesRequired(STRATEGY, TIMEFRAME, DIAS))
                        .thenReturn(FAKE_CANDLES);
                pc.when(() -> PathConfig.getValidStrategyPath(STRATEGY))
                        .thenReturn(FAKE_STRATEGY_PATH);

                when(fetchMarketDataUseCase.fetchIncremental(anyString(), anyString(), anyInt(), anyLong()))
                                .thenReturn(0L);
                when(velaRepo.findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(
                        anyString(), anyString(), anyLong()))
                        .thenReturn(crearVelasTestList(100));
                when(optimizationEnginePort.ejecutarOptimizacion(
                        anyString(), anyString(), anyMap(), anyString(), any(Double.class), any(), anyInt(), anyInt()))
                        .thenReturn(crearResultadoFallidoTest("optuna convergence failure"));

                assertThrows(StrategyExecutionException.class,
                        () -> optimizationService.optimizarHiperparametros(
                                MODELO, TIMEFRAME, SYMBOL, DIAS, STRATEGY, MIN_COMPOSITE, N_TRIALS, CV_FOLDS));
            }
        }

        @Test
        @DisplayName("✓ El mensaje de la excepción debe incluir el error del motor")
        void should_include_engine_error_in_exception_message() {
            String errorEsperado = "optuna convergence failure";
            ConsoleLoader loader = crearLoaderMock();
            try (MockedStatic<ConsoleLoader> cs = mockStatic(ConsoleLoader.class);
                 MockedStatic<StrategyInspector> si = mockStatic(StrategyInspector.class);
                 MockedStatic<PathConfig> pc = mockStatic(PathConfig.class)) {

                cs.when(ConsoleLoader::getInstance).thenReturn(loader);
                si.when(() -> StrategyInspector.getWarmupPeriod(STRATEGY)).thenReturn(FAKE_WARMUP);
                si.when(() -> StrategyInspector.getCandlesRequired(STRATEGY, TIMEFRAME, DIAS))
                        .thenReturn(FAKE_CANDLES);
                pc.when(() -> PathConfig.getValidStrategyPath(STRATEGY))
                        .thenReturn(FAKE_STRATEGY_PATH);

                when(fetchMarketDataUseCase.fetchIncremental(anyString(), anyString(), anyInt(), anyLong()))
                                .thenReturn(0L);
                when(velaRepo.findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(
                        anyString(), anyString(), anyLong()))
                        .thenReturn(crearVelasTestList(100));
                when(optimizationEnginePort.ejecutarOptimizacion(
                        anyString(), anyString(), anyMap(), anyString(), any(Double.class), any(), anyInt(), anyInt()))
                        .thenReturn(crearResultadoFallidoTest(errorEsperado));

                StrategyExecutionException ex = assertThrows(StrategyExecutionException.class,
                        () -> optimizationService.optimizarHiperparametros(
                                MODELO, TIMEFRAME, SYMBOL, DIAS, STRATEGY, MIN_COMPOSITE, N_TRIALS, CV_FOLDS));

                assertThat(ex.getMessage(), containsString(errorEsperado));
            }
        }

        @Test
        @DisplayName("✓ Debe pasar minComposite dividido entre 100 al motor")
        void should_pass_min_accuracy_divided_by_100_to_engine() {
            ConsoleLoader loader = crearLoaderMock();
            try (MockedStatic<ConsoleLoader> cs = mockStatic(ConsoleLoader.class);
                 MockedStatic<StrategyInspector> si = mockStatic(StrategyInspector.class);
                 MockedStatic<PathConfig> pc = mockStatic(PathConfig.class)) {

                cs.when(ConsoleLoader::getInstance).thenReturn(loader);
                si.when(() -> StrategyInspector.getWarmupPeriod(STRATEGY)).thenReturn(FAKE_WARMUP);
                si.when(() -> StrategyInspector.getCandlesRequired(STRATEGY, TIMEFRAME, DIAS))
                        .thenReturn(FAKE_CANDLES);
                pc.when(() -> PathConfig.getValidStrategyPath(STRATEGY))
                        .thenReturn(FAKE_STRATEGY_PATH);

                when(fetchMarketDataUseCase.fetchIncremental(anyString(), anyString(), anyInt(), anyLong()))
                                .thenReturn(0L);
                when(velaRepo.findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(
                        anyString(), anyString(), anyLong()))
                        .thenReturn(crearVelasTestList(100));
                when(optimizationEnginePort.ejecutarOptimizacion(
                        anyString(), anyString(), anyMap(), anyString(), any(Double.class), any(), anyInt(), anyInt()))
                        .thenReturn(crearResultadoExitosoTest());

                optimizationService.optimizarHiperparametros(
                        MODELO, TIMEFRAME, SYMBOL, DIAS, STRATEGY, MIN_COMPOSITE, N_TRIALS, CV_FOLDS);

                ArgumentCaptor<Double> accuracyCaptor = ArgumentCaptor.forClass(Double.class);
                verify(optimizationEnginePort).ejecutarOptimizacion(
                        anyString(), anyString(), anyMap(), anyString(),
                        accuracyCaptor.capture(), any(), anyInt(), anyInt());

                assertThat(accuracyCaptor.getValue(), is(MIN_COMPOSITE / 100.0));
            }
        }

        @Test
        @DisplayName("✓ Debe pasar el warmupCandles correcto al motor")
        void should_pass_warmup_candles_to_engine() {
            ConsoleLoader loader = crearLoaderMock();
            try (MockedStatic<ConsoleLoader> cs = mockStatic(ConsoleLoader.class);
                 MockedStatic<StrategyInspector> si = mockStatic(StrategyInspector.class);
                 MockedStatic<PathConfig> pc = mockStatic(PathConfig.class)) {

                cs.when(ConsoleLoader::getInstance).thenReturn(loader);
                si.when(() -> StrategyInspector.getWarmupPeriod(STRATEGY)).thenReturn(FAKE_WARMUP);
                si.when(() -> StrategyInspector.getCandlesRequired(STRATEGY, TIMEFRAME, DIAS))
                        .thenReturn(FAKE_CANDLES);
                pc.when(() -> PathConfig.getValidStrategyPath(STRATEGY))
                        .thenReturn(FAKE_STRATEGY_PATH);

                when(fetchMarketDataUseCase.fetchIncremental(anyString(), anyString(), anyInt(), anyLong()))
                                .thenReturn(0L);
                when(velaRepo.findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(
                        anyString(), anyString(), anyLong()))
                        .thenReturn(crearVelasTestList(100));
                when(optimizationEnginePort.ejecutarOptimizacion(
                        anyString(), anyString(), anyMap(), anyString(), any(Double.class), any(), anyInt(), anyInt()))
                        .thenReturn(crearResultadoExitosoTest());

                optimizationService.optimizarHiperparametros(
                        MODELO, TIMEFRAME, SYMBOL, DIAS, STRATEGY, MIN_COMPOSITE, N_TRIALS, CV_FOLDS);

                verify(optimizationEnginePort).ejecutarOptimizacion(
                        anyString(), anyString(), anyMap(), anyString(),
                        any(Double.class), eq(FAKE_WARMUP), anyInt(), anyInt());
            }
        }

        @Test
        @DisplayName("✓ Debe pasar el strategyPath correcto al motor")
        void should_pass_correct_strategy_path_to_engine() {
            ConsoleLoader loader = crearLoaderMock();
            try (MockedStatic<ConsoleLoader> cs = mockStatic(ConsoleLoader.class);
                 MockedStatic<StrategyInspector> si = mockStatic(StrategyInspector.class);
                 MockedStatic<PathConfig> pc = mockStatic(PathConfig.class)) {

                cs.when(ConsoleLoader::getInstance).thenReturn(loader);
                si.when(() -> StrategyInspector.getWarmupPeriod(STRATEGY)).thenReturn(FAKE_WARMUP);
                si.when(() -> StrategyInspector.getCandlesRequired(STRATEGY, TIMEFRAME, DIAS))
                        .thenReturn(FAKE_CANDLES);
                pc.when(() -> PathConfig.getValidStrategyPath(STRATEGY))
                        .thenReturn(FAKE_STRATEGY_PATH);

                when(fetchMarketDataUseCase.fetchIncremental(anyString(), anyString(), anyInt(), anyLong()))
                                .thenReturn(0L);
                when(velaRepo.findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(
                        anyString(), anyString(), anyLong()))
                        .thenReturn(crearVelasTestList(100));
                when(optimizationEnginePort.ejecutarOptimizacion(
                        anyString(), anyString(), anyMap(), anyString(), any(Double.class), any(), anyInt(), anyInt()))
                        .thenReturn(crearResultadoExitosoTest());

                optimizationService.optimizarHiperparametros(
                        MODELO, TIMEFRAME, SYMBOL, DIAS, STRATEGY, MIN_COMPOSITE, N_TRIALS, CV_FOLDS);

                verify(optimizationEnginePort).ejecutarOptimizacion(
                        eq(MODELO), eq(FAKE_STRATEGY_PATH), anyMap(),
                        eq(TIMEFRAME), any(Double.class), any(), anyInt(), anyInt());
            }
        }
    }

    @Nested
    @DisplayName("optimizarHiperparametros() — Flujo completo (happy path)")
    class FlujoCompletoTests {

        @Test
        @DisplayName("✓ Debe retornar String no nulo en flujo exitoso")
        void should_return_non_null_string_on_success() {
            ConsoleLoader loader = crearLoaderMock();
            try (MockedStatic<ConsoleLoader> cs = mockStatic(ConsoleLoader.class);
                 MockedStatic<StrategyInspector> si = mockStatic(StrategyInspector.class);
                 MockedStatic<PathConfig> pc = mockStatic(PathConfig.class)) {

                cs.when(ConsoleLoader::getInstance).thenReturn(loader);
                si.when(() -> StrategyInspector.getWarmupPeriod(STRATEGY)).thenReturn(FAKE_WARMUP);
                si.when(() -> StrategyInspector.getCandlesRequired(STRATEGY, TIMEFRAME, DIAS))
                        .thenReturn(FAKE_CANDLES);
                pc.when(() -> PathConfig.getValidStrategyPath(STRATEGY))
                        .thenReturn(FAKE_STRATEGY_PATH);

                when(fetchMarketDataUseCase.fetchIncremental(anyString(), anyString(), anyInt(), anyLong()))
                                .thenReturn(0L);
                when(velaRepo.findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(
                        anyString(), anyString(), anyLong()))
                        .thenReturn(crearVelasTestList(100));
                when(optimizationEnginePort.ejecutarOptimizacion(
                        anyString(), anyString(), anyMap(), anyString(), any(Double.class), any(), anyInt(), anyInt()))
                        .thenReturn(crearResultadoExitosoTest());

                String resultado = optimizationService.optimizarHiperparametros(
                        MODELO, TIMEFRAME, SYMBOL, DIAS, STRATEGY, MIN_COMPOSITE, N_TRIALS, CV_FOLDS);

                assertThat(resultado, is(notNullValue()));
            }
        }

        @Test
        @DisplayName("✓ El resultado debe contener información del estado")
        void should_contain_status_in_result() {
            ConsoleLoader loader = crearLoaderMock();
            try (MockedStatic<ConsoleLoader> cs = mockStatic(ConsoleLoader.class);
                 MockedStatic<StrategyInspector> si = mockStatic(StrategyInspector.class);
                 MockedStatic<PathConfig> pc = mockStatic(PathConfig.class)) {

                cs.when(ConsoleLoader::getInstance).thenReturn(loader);
                si.when(() -> StrategyInspector.getWarmupPeriod(STRATEGY)).thenReturn(FAKE_WARMUP);
                si.when(() -> StrategyInspector.getCandlesRequired(STRATEGY, TIMEFRAME, DIAS))
                        .thenReturn(FAKE_CANDLES);
                pc.when(() -> PathConfig.getValidStrategyPath(STRATEGY))
                        .thenReturn(FAKE_STRATEGY_PATH);

                when(fetchMarketDataUseCase.fetchIncremental(anyString(), anyString(), anyInt(), anyLong()))
                                .thenReturn(0L);
                when(velaRepo.findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(
                        anyString(), anyString(), anyLong()))
                        .thenReturn(crearVelasTestList(100));
                when(optimizationEnginePort.ejecutarOptimizacion(
                        anyString(), anyString(), anyMap(), anyString(), any(Double.class), any(), anyInt(), anyInt()))
                        .thenReturn(crearResultadoExitosoTest());

                String resultado = optimizationService.optimizarHiperparametros(
                        MODELO, TIMEFRAME, SYMBOL, DIAS, STRATEGY, MIN_COMPOSITE, N_TRIALS, CV_FOLDS);

                assertThat(resultado, containsString("Estado"));
            }
        }

        @Test
        @DisplayName("✓ El resultado debe contener información de trials")
        void should_contain_trials_info_in_result() {
            ConsoleLoader loader = crearLoaderMock();
            try (MockedStatic<ConsoleLoader> cs = mockStatic(ConsoleLoader.class);
                 MockedStatic<StrategyInspector> si = mockStatic(StrategyInspector.class);
                 MockedStatic<PathConfig> pc = mockStatic(PathConfig.class)) {

                cs.when(ConsoleLoader::getInstance).thenReturn(loader);
                si.when(() -> StrategyInspector.getWarmupPeriod(STRATEGY)).thenReturn(FAKE_WARMUP);
                si.when(() -> StrategyInspector.getCandlesRequired(STRATEGY, TIMEFRAME, DIAS))
                        .thenReturn(FAKE_CANDLES);
                pc.when(() -> PathConfig.getValidStrategyPath(STRATEGY))
                        .thenReturn(FAKE_STRATEGY_PATH);

                when(fetchMarketDataUseCase.fetchIncremental(anyString(), anyString(), anyInt(), anyLong()))
                                .thenReturn(0L);
                when(velaRepo.findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(
                        anyString(), anyString(), anyLong()))
                        .thenReturn(crearVelasTestList(100));
                when(optimizationEnginePort.ejecutarOptimizacion(
                        anyString(), anyString(), anyMap(), anyString(), any(Double.class), any(), anyInt(), anyInt()))
                        .thenReturn(crearResultadoExitosoTest());

                String resultado = optimizationService.optimizarHiperparametros(
                        MODELO, TIMEFRAME, SYMBOL, DIAS, STRATEGY, MIN_COMPOSITE, N_TRIALS, CV_FOLDS);

                assertThat(resultado, containsString("Trials"));
            }
        }

        @Test
        @DisplayName("✓ Debe invocar el motor de optimización exactamente una vez")
        void should_invoke_engine_exactly_once() {
            ConsoleLoader loader = crearLoaderMock();
            try (MockedStatic<ConsoleLoader> cs = mockStatic(ConsoleLoader.class);
                 MockedStatic<StrategyInspector> si = mockStatic(StrategyInspector.class);
                 MockedStatic<PathConfig> pc = mockStatic(PathConfig.class)) {

                cs.when(ConsoleLoader::getInstance).thenReturn(loader);
                si.when(() -> StrategyInspector.getWarmupPeriod(STRATEGY)).thenReturn(FAKE_WARMUP);
                si.when(() -> StrategyInspector.getCandlesRequired(STRATEGY, TIMEFRAME, DIAS))
                        .thenReturn(FAKE_CANDLES);
                pc.when(() -> PathConfig.getValidStrategyPath(STRATEGY))
                        .thenReturn(FAKE_STRATEGY_PATH);

                when(fetchMarketDataUseCase.fetchIncremental(anyString(), anyString(), anyInt(), anyLong()))
                                .thenReturn(0L);
                when(velaRepo.findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(
                        anyString(), anyString(), anyLong()))
                        .thenReturn(crearVelasTestList(100));
                when(optimizationEnginePort.ejecutarOptimizacion(
                        anyString(), anyString(), anyMap(), anyString(), any(Double.class), any(), anyInt(), anyInt()))
                        .thenReturn(crearResultadoExitosoTest());

                optimizationService.optimizarHiperparametros(
                        MODELO, TIMEFRAME, SYMBOL, DIAS, STRATEGY, MIN_COMPOSITE, N_TRIALS, CV_FOLDS);

                verify(optimizationEnginePort)
                        .ejecutarOptimizacion(anyString(), anyString(), anyMap(),
                                anyString(), any(Double.class), any(), anyInt(), anyInt());
            }
        }

        @Test
        @DisplayName("✓ Debe invocar fetchIncremental con symbol y timeframe correctos")
        void should_invoke_fetch_with_correct_params() {
            ConsoleLoader loader = crearLoaderMock();
            try (MockedStatic<ConsoleLoader> cs = mockStatic(ConsoleLoader.class);
                 MockedStatic<StrategyInspector> si = mockStatic(StrategyInspector.class);
                 MockedStatic<PathConfig> pc = mockStatic(PathConfig.class)) {

                cs.when(ConsoleLoader::getInstance).thenReturn(loader);
                si.when(() -> StrategyInspector.getWarmupPeriod(STRATEGY)).thenReturn(FAKE_WARMUP);
                si.when(() -> StrategyInspector.getCandlesRequired(STRATEGY, TIMEFRAME, DIAS))
                        .thenReturn(FAKE_CANDLES);
                pc.when(() -> PathConfig.getValidStrategyPath(STRATEGY))
                        .thenReturn(FAKE_STRATEGY_PATH);

                when(fetchMarketDataUseCase.fetchIncremental(anyString(), anyString(), anyInt(), anyLong()))
                                .thenReturn(0L);
                when(velaRepo.findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(
                        anyString(), anyString(), anyLong()))
                        .thenReturn(Collections.emptyList());

                optimizationService.optimizarHiperparametros(
                        MODELO, TIMEFRAME, SYMBOL, DIAS, STRATEGY, MIN_COMPOSITE, N_TRIALS, CV_FOLDS);

                verify(fetchMarketDataUseCase)
                        .fetchIncremental(eq(SYMBOL), eq(TIMEFRAME), anyInt(), anyLong());
            }
        }
    }

    @Nested
    @DisplayName("optimizarHiperparametros() — Manejo de excepciones")
    class ManejoExcepcionesTests {

        @Test
        @DisplayName("✓ Debe relanzar StrategyExecutionException sin envolver")
        void should_rethrow_strategy_execution_exception_directly() {
            ConsoleLoader loader = crearLoaderMock();
            try (MockedStatic<ConsoleLoader> cs = mockStatic(ConsoleLoader.class);
                 MockedStatic<StrategyInspector> si = mockStatic(StrategyInspector.class);
                 MockedStatic<PathConfig> pc = mockStatic(PathConfig.class)) {

                cs.when(ConsoleLoader::getInstance).thenReturn(loader);
                si.when(() -> StrategyInspector.getWarmupPeriod(STRATEGY)).thenReturn(FAKE_WARMUP);
                si.when(() -> StrategyInspector.getCandlesRequired(STRATEGY, TIMEFRAME, DIAS))
                        .thenReturn(FAKE_CANDLES);
                pc.when(() -> PathConfig.getValidStrategyPath(STRATEGY))
                        .thenReturn(FAKE_STRATEGY_PATH);

                when(fetchMarketDataUseCase.fetchIncremental(anyString(), anyString(), anyInt(), anyLong()))
                                .thenReturn(0L);
                when(velaRepo.findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(
                        anyString(), anyString(), anyLong()))
                        .thenReturn(crearVelasTestList(100));

                String mensajeOriginal = "fallo controlado en motor optimización";
                when(optimizationEnginePort.ejecutarOptimizacion(
                        anyString(), anyString(), anyMap(), anyString(), any(Double.class), any(), anyInt(), anyInt()))
                        .thenThrow(new StrategyExecutionException(mensajeOriginal));

                StrategyExecutionException ex = assertThrows(StrategyExecutionException.class,
                        () -> optimizationService.optimizarHiperparametros(
                                MODELO, TIMEFRAME, SYMBOL, DIAS, STRATEGY, MIN_COMPOSITE, N_TRIALS, CV_FOLDS));

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
                si.when(() -> StrategyInspector.getWarmupPeriod(STRATEGY)).thenReturn(FAKE_WARMUP);
                si.when(() -> StrategyInspector.getCandlesRequired(STRATEGY, TIMEFRAME, DIAS))
                        .thenReturn(FAKE_CANDLES);
                pc.when(() -> PathConfig.getValidStrategyPath(STRATEGY))
                        .thenReturn(FAKE_STRATEGY_PATH);

                when(fetchMarketDataUseCase.fetchIncremental(anyString(), anyString(), anyInt(), anyLong()))
                                .thenReturn(0L);
                when(velaRepo.findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(
                        anyString(), anyString(), anyLong()))
                        .thenThrow(new RuntimeException("fallo inesperado en repositorio"));

                assertThrows(StrategyExecutionException.class,
                        () -> optimizationService.optimizarHiperparametros(
                                MODELO, TIMEFRAME, SYMBOL, DIAS, STRATEGY, MIN_COMPOSITE, N_TRIALS, CV_FOLDS));
            }
        }

        @Test
        @DisplayName("✓ La excepción envolvente debe incluir el mensaje original")
        void should_preserve_original_message_when_wrapping_exception() {
            String mensajeOriginal = "fallo inesperado en repositorio";
            ConsoleLoader loader = crearLoaderMock();
            try (MockedStatic<ConsoleLoader> cs = mockStatic(ConsoleLoader.class);
                 MockedStatic<StrategyInspector> si = mockStatic(StrategyInspector.class);
                 MockedStatic<PathConfig> pc = mockStatic(PathConfig.class)) {

                cs.when(ConsoleLoader::getInstance).thenReturn(loader);
                si.when(() -> StrategyInspector.getWarmupPeriod(STRATEGY)).thenReturn(FAKE_WARMUP);
                si.when(() -> StrategyInspector.getCandlesRequired(STRATEGY, TIMEFRAME, DIAS))
                        .thenReturn(FAKE_CANDLES);
                pc.when(() -> PathConfig.getValidStrategyPath(STRATEGY))
                        .thenReturn(FAKE_STRATEGY_PATH);

                when(fetchMarketDataUseCase.fetchIncremental(anyString(), anyString(), anyInt(), anyLong()))
                                .thenReturn(0L);
                when(velaRepo.findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(
                        anyString(), anyString(), anyLong()))
                        .thenThrow(new RuntimeException(mensajeOriginal));

                StrategyExecutionException ex = assertThrows(StrategyExecutionException.class,
                        () -> optimizationService.optimizarHiperparametros(
                                MODELO, TIMEFRAME, SYMBOL, DIAS, STRATEGY, MIN_COMPOSITE, N_TRIALS, CV_FOLDS));

                assertThat(ex.getMessage(), containsString(mensajeOriginal));
            }
        }
    }

    @Nested
    @DisplayName("optimizarHiperparametros() — Cálculo de minComposite")
    class MinAccuracyCalculationTests {

        @Test
        @DisplayName("✓ 80.0 de minComposite se convierte en 0.8 para el motor")
        void should_convert_80_percent_to_0_point_8() {
            ConsoleLoader loader = crearLoaderMock();
            try (MockedStatic<ConsoleLoader> cs = mockStatic(ConsoleLoader.class);
                 MockedStatic<StrategyInspector> si = mockStatic(StrategyInspector.class);
                 MockedStatic<PathConfig> pc = mockStatic(PathConfig.class)) {

                cs.when(ConsoleLoader::getInstance).thenReturn(loader);
                si.when(() -> StrategyInspector.getWarmupPeriod(STRATEGY)).thenReturn(FAKE_WARMUP);
                si.when(() -> StrategyInspector.getCandlesRequired(STRATEGY, TIMEFRAME, DIAS))
                        .thenReturn(FAKE_CANDLES);
                pc.when(() -> PathConfig.getValidStrategyPath(STRATEGY))
                        .thenReturn(FAKE_STRATEGY_PATH);

                when(fetchMarketDataUseCase.fetchIncremental(anyString(), anyString(), anyInt(), anyLong()))
                                .thenReturn(0L);
                when(velaRepo.findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(
                        anyString(), anyString(), anyLong()))
                        .thenReturn(crearVelasTestList(100));
                when(optimizationEnginePort.ejecutarOptimizacion(
                        anyString(), anyString(), anyMap(), anyString(), any(Double.class), any(), anyInt(), anyInt()))
                        .thenReturn(crearResultadoExitosoTest());

                optimizationService.optimizarHiperparametros(
                        MODELO, TIMEFRAME, SYMBOL, DIAS, STRATEGY, 80.0, N_TRIALS, CV_FOLDS);

                ArgumentCaptor<Double> captor = ArgumentCaptor.forClass(Double.class);
                verify(optimizationEnginePort).ejecutarOptimizacion(
                        anyString(), anyString(), anyMap(), anyString(), captor.capture(), any(), anyInt(), anyInt());

                assertThat(captor.getValue(), is(0.8));
            }
        }

        @Test
        @DisplayName("✓ 50.0 de minComposite se convierte en 0.5 para el motor")
        void should_convert_50_percent_to_0_point_5() {
            ConsoleLoader loader = crearLoaderMock();
            try (MockedStatic<ConsoleLoader> cs = mockStatic(ConsoleLoader.class);
                 MockedStatic<StrategyInspector> si = mockStatic(StrategyInspector.class);
                 MockedStatic<PathConfig> pc = mockStatic(PathConfig.class)) {

                cs.when(ConsoleLoader::getInstance).thenReturn(loader);
                si.when(() -> StrategyInspector.getWarmupPeriod(STRATEGY)).thenReturn(FAKE_WARMUP);
                si.when(() -> StrategyInspector.getCandlesRequired(STRATEGY, TIMEFRAME, DIAS))
                        .thenReturn(FAKE_CANDLES);
                pc.when(() -> PathConfig.getValidStrategyPath(STRATEGY))
                        .thenReturn(FAKE_STRATEGY_PATH);

                when(fetchMarketDataUseCase.fetchIncremental(anyString(), anyString(), anyInt(), anyLong()))
                                .thenReturn(0L);
                when(velaRepo.findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(
                        anyString(), anyString(), anyLong()))
                        .thenReturn(crearVelasTestList(100));
                when(optimizationEnginePort.ejecutarOptimizacion(
                        anyString(), anyString(), anyMap(), anyString(), any(Double.class), any(), anyInt(), anyInt()))
                        .thenReturn(crearResultadoExitosoTest());

                optimizationService.optimizarHiperparametros(
                        MODELO, TIMEFRAME, SYMBOL, DIAS, STRATEGY, 50.0, N_TRIALS, CV_FOLDS);

                ArgumentCaptor<Double> captor = ArgumentCaptor.forClass(Double.class);
                verify(optimizationEnginePort).ejecutarOptimizacion(
                        anyString(), anyString(), anyMap(), anyString(), captor.capture(), any(), anyInt(), anyInt());

                assertThat(captor.getValue(), is(0.5));
            }
        }
    }
}
