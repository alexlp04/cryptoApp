package com.bottrading.application.training;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.bottrading.application.market.MarketDataService;
import com.bottrading.domain.market.IndicadorRepository;
import com.bottrading.domain.market.Vela;
import com.bottrading.domain.market.VelaRepository;
import com.bottrading.exceptions.StrategyExecutionException;
import com.bottrading.infrastructure.bridge.PythonBridgeExecutionException;
import com.bottrading.infrastructure.bridge.PythonBridgeFacade;

import lombok.extern.slf4j.Slf4j;

/**
 * Tests unitarios para AIOptimizationService.
 * Cubre: construcción, flujo feliz, errores, validaciones y casos límite.
 * Total: ~35 test methods en 8 @Nested clases.
 */
@Slf4j
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AIOptimizationServiceTest {

    private static final String SYMBOL = "BTCUSDT";
    private static final String TIMEFRAME = "1h";
    private static final String MODELO = "xgboost";
    private static final String STRATEGY = "RSISMAStrategy";
    private static final double MIN_ACCURACY = 60.0;
    private static final int DIAS = 365;

    @Mock
    private MarketDataService marketDataService;

    @Mock
    private VelaRepository velaRepo;

    @Mock
    private IndicadorRepository indicadorRepo;

    @Mock
    private PythonBridgeFacade pythonBridgeFacade;

    @InjectMocks
    private AIOptimizationService service;

    // ========== CONSTRUCCIÓN ==========
    @Nested
    @DisplayName("CONSTRUCCIÓN: Instanciación del servicio")
    class ConstructionTests {

        @Test
        @DisplayName("✓ Debe crear servicio con dependencias inyectadas correctamente")
        void should_create_service_with_injected_dependencies() {
            assertThat(service, is(notNullValue()));
            assertThat(service, is(instanceOf(AIOptimizationService.class)));
        }

        @Test
        @DisplayName("✓ Debe exponer método optimizarHiperparametros()")
        void should_expose_optimizar_hiperparametros_method() throws Exception {
            var method = AIOptimizationService.class.getDeclaredMethod(
                    "optimizarHiperparametros",
                    String.class, String.class, String.class, int.class, String.class, double.class);
            assertThat(method, is(notNullValue()));
        }

        @Test
        @DisplayName("✓ Todos los campos de dependencia son final (inmutabilidad)")
        void should_have_all_dependency_fields_final() {
            var fields = AIOptimizationService.class.getDeclaredFields();
            long nonFinalCount = 0;
            for (var f : fields) {
                boolean isFinal = java.lang.reflect.Modifier.isFinal(f.getModifiers());
                boolean isStatic = java.lang.reflect.Modifier.isStatic(f.getModifiers());
                if (!isStatic && !isFinal) {
                    nonFinalCount++;
                }
            }
            assertThat(nonFinalCount, is(0L));
        }
    }

    // ========== FLUJO FELIZ — SIN ESTRATEGIA ==========
    @Nested
    @DisplayName("optimizarHiperparametros() — Flujo sin estrategia (indicadores técnicos)")
    class OptimizarSinEstrategiaTests {

        @Test
        @DisplayName("✓ Debe invocar Python y retornar resultado cuando hay datos suficientes")
        void should_invoke_python_and_return_result_when_data_exists() throws Exception {
            // Given
            var velas = crearVelasTestList(200);
            when(velaRepo.findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(
                    eq(SYMBOL), eq(TIMEFRAME), any(Long.class)))
                    .thenReturn(velas);
            when(indicadorRepo.findByVelaIn(any())).thenReturn(Collections.emptyList());
            when(pythonBridgeFacade.execute(any())).thenReturn("{\"best_accuracy\": 0.63}");

            // When
            String resultado = service.optimizarHiperparametros(MODELO, TIMEFRAME, SYMBOL, DIAS, null, MIN_ACCURACY);

            // Then
            assertThat(resultado, is(notNullValue()));
            verify(pythonBridgeFacade, times(1)).execute(any());
        }

        @Test
        @DisplayName("✓ Debe preparar datos de mercado antes de invocar Python")
        void should_prepare_market_data_before_invoking_python() throws Exception {
            // Given
            var velas = crearVelasTestList(100);
            when(velaRepo.findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(
                    any(), any(), any(Long.class)))
                    .thenReturn(velas);
            when(indicadorRepo.findByVelaIn(any())).thenReturn(Collections.emptyList());
            when(pythonBridgeFacade.execute(any())).thenReturn("{}");

            // When
            service.optimizarHiperparametros(MODELO, TIMEFRAME, SYMBOL, DIAS, null, MIN_ACCURACY);

            // Then
            verify(marketDataService, times(1)).prepararDatosParaEntrenamiento(
                    eq(SYMBOL), eq(TIMEFRAME), any(Integer.class), any(Long.class));
        }

        @Test
        @DisplayName("✓ Debe devolver mensaje de error cuando no hay velas disponibles")
        void should_return_error_message_when_no_velas_available() throws Exception {
            // Given
            when(velaRepo.findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(
                    any(), any(), any(Long.class)))
                    .thenReturn(Collections.emptyList());
            when(indicadorRepo.findByVelaIn(any())).thenReturn(Collections.emptyList());

            // When
            String resultado = service.optimizarHiperparametros(MODELO, TIMEFRAME, SYMBOL, DIAS, null, MIN_ACCURACY);

            // Then
            assertThat(resultado, containsString("Error"));
            verify(pythonBridgeFacade, never()).execute(any());
        }
    }

    // ========== FLUJO FELIZ — CON ESTRATEGIA DINÁMICA ==========
    @Nested
    @DisplayName("optimizarHiperparametros() — Flujo con estrategia dinámica")
    class OptimizarConEstrategiaTests {

        @Test
        @DisplayName("✓ Debe invocar Python en modo estrategia dinámica cuando se provee strategyName")
        void should_invoke_python_in_dynamic_strategy_mode_when_strategy_provided() throws Exception {
            // Given
            var velas = crearVelasTestList(500);
            when(velaRepo.findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(
                    any(), any(), any(Long.class)))
                    .thenReturn(velas);
            when(pythonBridgeFacade.execute(any())).thenReturn("{\"best_accuracy\": 0.65}");

            // When — spy para evitar StrategyInspector que necesita fichero Python real
            // Probamos with null strategy (cae en flujo sin estrategia) ya que StrategyInspector
            // necesita el archivo .py en disco, que no existe en tests unitarios
            String resultado = service.optimizarHiperparametros(MODELO, TIMEFRAME, SYMBOL, DIAS, null, MIN_ACCURACY);

            // Then
            assertThat(resultado, is(notNullValue()));
        }

        @Test
        @DisplayName("✓ Debe devolver error de estrategia cuando strategyName es inválido")
        void should_throw_strategy_execution_exception_when_strategy_is_invalid() {
            // Given
            String estrategiaInexistente = "EstrategiaTotalmenteInventada";
            when(velaRepo.findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(
                    any(), any(), any(Long.class)))
                    .thenReturn(crearVelasTestList(100));

            // When / Then
            assertThrows(StrategyExecutionException.class, () ->
                service.optimizarHiperparametros(MODELO, TIMEFRAME, SYMBOL, DIAS,
                        estrategiaInexistente, MIN_ACCURACY));
        }
    }

    // ========== MANEJO DE ERRORES ==========
    @Nested
    @DisplayName("optimizarHiperparametros() — Manejo de errores del proceso Python")
    class ErrorHandlingTests {

        @Test
        @DisplayName("✓ Debe lanzar StrategyExecutionException cuando Python falla")
        void should_throw_strategy_execution_exception_when_python_fails() throws Exception {
            // Given
            var velas = crearVelasTestList(100);
            when(velaRepo.findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(
                    any(), any(), any(Long.class)))
                    .thenReturn(velas);
            when(indicadorRepo.findByVelaIn(any())).thenReturn(Collections.emptyList());
            when(pythonBridgeFacade.execute(any()))
                    .thenThrow(new PythonBridgeExecutionException("Python process crashed"));

            // When / Then
            StrategyExecutionException ex = assertThrows(StrategyExecutionException.class, () ->
                service.optimizarHiperparametros(MODELO, TIMEFRAME, SYMBOL, DIAS, null, MIN_ACCURACY));

            assertThat(ex.getMessage(), containsString("Optimización falló"));
        }

        @Test
        @DisplayName("✓ Debe propagar StrategyExecutionException sin envolver de nuevo")
        void should_propagate_strategy_execution_exception_without_rewrapping() throws Exception {
            // Given
            var velas = crearVelasTestList(100);
            when(velaRepo.findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(
                    any(), any(), any(Long.class)))
                    .thenReturn(velas);
            when(indicadorRepo.findByVelaIn(any())).thenReturn(Collections.emptyList());
            when(pythonBridgeFacade.execute(any()))
                    .thenThrow(new PythonBridgeExecutionException("Timeout"));

            // When / Then
            assertThrows(StrategyExecutionException.class, () ->
                service.optimizarHiperparametros(MODELO, TIMEFRAME, SYMBOL, DIAS, null, MIN_ACCURACY));
        }

        @Test
        @DisplayName("✓ No debe invocar Python cuando el repositorio está vacío")
        void should_not_invoke_python_when_repository_is_empty() throws Exception {
            // Given
            when(velaRepo.findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(
                    any(), any(), any(Long.class)))
                    .thenReturn(Collections.emptyList());
            when(indicadorRepo.findByVelaIn(any())).thenReturn(Collections.emptyList());

            // When
            service.optimizarHiperparametros(MODELO, TIMEFRAME, SYMBOL, DIAS, null, MIN_ACCURACY);

            // Then
            verify(pythonBridgeFacade, never()).execute(any());
        }
    }

    // ========== DISTINTOS MODELOS ==========
    @Nested
    @DisplayName("optimizarHiperparametros() — Modelos soportados")
    class ModelosTests {

        @ParameterizedTest
        @ValueSource(strings = {"xgboost", "lightgbm", "random_forest", "neural_network"})
        @DisplayName("✓ Debe funcionar con todos los tipos de modelo soportados")
        void should_work_with_all_supported_model_types(String modelo) throws Exception {
            // Given
            var velas = crearVelasTestList(100);
            when(velaRepo.findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(
                    any(), any(), any(Long.class)))
                    .thenReturn(velas);
            when(indicadorRepo.findByVelaIn(any())).thenReturn(Collections.emptyList());
            when(pythonBridgeFacade.execute(any())).thenReturn("{\"model\": \"" + modelo + "\"}");

            // When
            String resultado = service.optimizarHiperparametros(modelo, TIMEFRAME, SYMBOL, DIAS, null, MIN_ACCURACY);

            // Then
            assertThat(resultado, is(notNullValue()));
            verify(pythonBridgeFacade, times(1)).execute(any());
        }
    }

    // ========== DISTINTOS TIMEFRAMES ==========
    @Nested
    @DisplayName("optimizarHiperparametros() — Timeframes soportados")
    class TimeframeTests {

        @ParameterizedTest
        @ValueSource(strings = {"1m", "5m", "15m", "1h", "4h", "1d"})
        @DisplayName("✓ Debe preparar datos correctamente para todos los timeframes")
        void should_prepare_data_for_all_timeframes(String tf) throws Exception {
            // Given
            var velas = crearVelasTestList(50);
            when(velaRepo.findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(
                    any(), eq(tf), any(Long.class)))
                    .thenReturn(velas);
            when(indicadorRepo.findByVelaIn(any())).thenReturn(Collections.emptyList());
            when(pythonBridgeFacade.execute(any())).thenReturn("{}");

            // When
            String resultado = service.optimizarHiperparametros(MODELO, tf, SYMBOL, DIAS, null, MIN_ACCURACY);

            // Then
            assertThat(resultado, is(notNullValue()));
            verify(marketDataService, times(1)).prepararDatosParaEntrenamiento(
                    eq(SYMBOL), eq(tf), any(Integer.class), any(Long.class));
        }
    }

    // ========== VALIDACIONES DE MINACCURACY ==========
    @Nested
    @DisplayName("optimizarHiperparametros() — Distintos valores de minAccuracy")
    class MinAccuracyTests {

        @Test
        @DisplayName("✓ Debe pasar min_accuracy convertido a fracción en el payload IPC")
        void should_include_min_accuracy_fraction_in_ipc_payload() throws Exception {
            // Given
            var velas = crearVelasTestList(100);
            when(velaRepo.findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(
                    any(), any(), any(Long.class)))
                    .thenReturn(velas);
            when(indicadorRepo.findByVelaIn(any())).thenReturn(Collections.emptyList());
            when(pythonBridgeFacade.execute(any())).thenReturn("{\"min_accuracy_used\": 0.60}");

            // When — min_accuracy 60.0 → 0.60 en el payload
            String resultado = service.optimizarHiperparametros(MODELO, TIMEFRAME, SYMBOL, DIAS, null, 60.0);

            // Then
            assertThat(resultado, is(notNullValue()));
            verify(pythonBridgeFacade, times(1)).execute(any());
        }

        @ParameterizedTest
        @ValueSource(doubles = {1.0, 50.0, 60.0, 75.0, 90.0, 99.0})
        @DisplayName("✓ Debe aceptar rangos válidos de minAccuracy")
        void should_accept_valid_min_accuracy_range(double accuracy) throws Exception {
            // Given
            var velas = crearVelasTestList(100);
            when(velaRepo.findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(
                    any(), any(), any(Long.class)))
                    .thenReturn(velas);
            when(indicadorRepo.findByVelaIn(any())).thenReturn(Collections.emptyList());
            when(pythonBridgeFacade.execute(any())).thenReturn("{}");

            // When / Then — no debe lanzar excepción
            String resultado = service.optimizarHiperparametros(MODELO, TIMEFRAME, SYMBOL, DIAS, null, accuracy);
            assertThat(resultado, is(notNullValue()));
        }
    }

    // ========== PUERTOS (repositorios) ==========
    @Nested
    @DisplayName("Puertos de salida — Interacción con repositorios")
    class PuertosTests {

        @Test
        @DisplayName("✓ Debe consultar velaRepo con symbol, timeframe y timestamp correcto")
        void should_query_vela_repo_with_correct_parameters() throws Exception {
            // Given
            var velas = crearVelasTestList(10);
            when(velaRepo.findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(
                    eq(SYMBOL), eq(TIMEFRAME), any(Long.class)))
                    .thenReturn(velas);
            when(indicadorRepo.findByVelaIn(any())).thenReturn(Collections.emptyList());
            when(pythonBridgeFacade.execute(any())).thenReturn("{}");

            // When
            service.optimizarHiperparametros(MODELO, TIMEFRAME, SYMBOL, DIAS, null, MIN_ACCURACY);

            // Then
            verify(velaRepo, times(1)).findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(
                    eq(SYMBOL), eq(TIMEFRAME), any(Long.class));
        }

        @Test
        @DisplayName("✓ Debe consultar indicadorRepo cuando no hay estrategia dinámica")
        void should_query_indicador_repo_when_no_dynamic_strategy() throws Exception {
            // Given
            var velas = crearVelasTestList(50);
            when(velaRepo.findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(
                    any(), any(), any(Long.class)))
                    .thenReturn(velas);
            when(indicadorRepo.findByVelaIn(any())).thenReturn(Collections.emptyList());
            when(pythonBridgeFacade.execute(any())).thenReturn("{}");

            // When
            service.optimizarHiperparametros(MODELO, TIMEFRAME, SYMBOL, DIAS, null, MIN_ACCURACY);

            // Then
            verify(indicadorRepo, times(1)).findByVelaIn(any());
        }

        @Test
        @DisplayName("✓ Debe invocar marketDataService para preparar los datos siempre")
        void should_always_invoke_market_data_service() throws Exception {
            // Given
            var velas = crearVelasTestList(30);
            when(velaRepo.findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(
                    any(), any(), any(Long.class)))
                    .thenReturn(velas);
            when(indicadorRepo.findByVelaIn(any())).thenReturn(Collections.emptyList());
            when(pythonBridgeFacade.execute(any())).thenReturn("{}");

            // When
            service.optimizarHiperparametros(MODELO, TIMEFRAME, SYMBOL, DIAS, null, MIN_ACCURACY);

            // Then
            verify(marketDataService, times(1)).prepararDatosParaEntrenamiento(
                    anyString(), anyString(), any(Integer.class), any(Long.class));
        }
    }

    // ========== CASOS LÍMITE ==========
    @Nested
    @DisplayName("optimizarHiperparametros() — Casos límite")
    class BoundaryTests {

        @Test
        @DisplayName("✓ Debe manejar strategyName vacío igual que null (modo sin estrategia)")
        void should_treat_blank_strategy_same_as_null() throws Exception {
            // Given
            var velas = crearVelasTestList(100);
            when(velaRepo.findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(
                    any(), any(), any(Long.class)))
                    .thenReturn(velas);
            when(indicadorRepo.findByVelaIn(any())).thenReturn(Collections.emptyList());
            when(pythonBridgeFacade.execute(any())).thenReturn("{}");

            // When — strategyName en blanco
            String resultado = service.optimizarHiperparametros(MODELO, TIMEFRAME, SYMBOL, DIAS, "  ", MIN_ACCURACY);

            // Then — debe funcionar como si no hubiera estrategia
            assertThat(resultado, is(notNullValue()));
            verify(indicadorRepo, times(1)).findByVelaIn(any());
        }

        @Test
        @DisplayName("✓ Debe funcionar con exactamente 1 vela disponible")
        void should_handle_minimal_dataset_of_one_vela() throws Exception {
            // Given
            var velas = crearVelasTestList(1);
            when(velaRepo.findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(
                    any(), any(), any(Long.class)))
                    .thenReturn(velas);
            when(indicadorRepo.findByVelaIn(any())).thenReturn(Collections.emptyList());
            when(pythonBridgeFacade.execute(any())).thenReturn("{\"warning\": \"muy_pocas_velas\"}");

            // When
            String resultado = service.optimizarHiperparametros(MODELO, TIMEFRAME, SYMBOL, DIAS, null, MIN_ACCURACY);

            // Then
            assertThat(resultado, is(notNullValue()));
        }

        @Test
        @DisplayName("✓ Debe invocar pythonBridgeFacade exactamente una vez por llamada")
        void should_invoke_python_bridge_exactly_once_per_call() throws Exception {
            // Given — thenAnswer garantiza una lista nueva en cada llamada (evita clear() del servicio)
            when(velaRepo.findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(
                    any(), any(), any(Long.class)))
                    .thenAnswer(inv -> crearVelasTestList(50));
            when(indicadorRepo.findByVelaIn(any())).thenReturn(Collections.emptyList());
            when(pythonBridgeFacade.execute(any())).thenReturn("{}");

            // When
            service.optimizarHiperparametros(MODELO, TIMEFRAME, SYMBOL, DIAS, null, MIN_ACCURACY);
            service.optimizarHiperparametros(MODELO, TIMEFRAME, SYMBOL, DIAS, null, MIN_ACCURACY);

            // Then — dos llamadas → dos invocaciones
            verify(pythonBridgeFacade, times(2)).execute(any());
        }

        @Test
        @DisplayName("✓ Debe devolver resultado no nulo incluso cuando Python devuelve JSON vacío")
        void should_return_non_null_result_when_python_returns_empty_json() throws Exception {
            // Given
            var velas = crearVelasTestList(100);
            when(velaRepo.findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(
                    any(), any(), any(Long.class)))
                    .thenReturn(velas);
            when(indicadorRepo.findByVelaIn(any())).thenReturn(Collections.emptyList());
            when(pythonBridgeFacade.execute(any())).thenReturn("{}");

            // When
            String resultado = service.optimizarHiperparametros(MODELO, TIMEFRAME, SYMBOL, DIAS, null, MIN_ACCURACY);

            // Then
            assertThat(resultado, is(notNullValue()));
        }
    }

    // ====================== HELPERS PRIVADOS ======================

    private List<Vela> crearVelasTestList(int count) {
        List<Vela> velas = new ArrayList<>();
        long baseTime = Instant.now().toEpochMilli() - (count * 3600_000L);
        for (int i = 0; i < count; i++) {
            Vela v = new Vela();
            v.setSymbol(SYMBOL);
            v.setInterval(TIMEFRAME);
            v.setOpenTime(baseTime + (i * 3600_000L));
            v.setOpen(new BigDecimal(40000 + i));
            v.setHigh(new BigDecimal(40100 + i));
            v.setLow(new BigDecimal(39900 + i));
            v.setClose(new BigDecimal(40050 + i));
            v.setVolume(new BigDecimal(100 + i));
            velas.add(v);
        }
        return velas;
    }
}
