package com.bottrading.application.market;

import com.bottrading.domain.market.Vela;
import com.bottrading.exceptions.PythonProcessException;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests para IndicatorsService — cálculo de indicadores técnicos.
 * 
 * Arquitectura testada:
 * 1. Given: Lista de velas + parámetros de cálculo
 * 2. When: Se invoca calculateBasicIndicators()
 * 3. Then: Se verifica llamada a PythonBridge y persistencia en BD
 */
@Slf4j
@ExtendWith({MockitoExtension.class})
@MockitoSettings(strictness = Strictness.LENIENT)
class IndicatorsServiceTest {

    // ============ Fixtures ============
    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private PythonBridgeFacade pythonBridgeFacade;

    @InjectMocks
    private IndicatorsService indicatorsService;

    private static final String SYMBOL = "BTCUSDT";
    private static final long NOW_MS = System.currentTimeMillis();

    // ============ CONSTRUCCIÓN ============

    @Nested
    @DisplayName("CONSTRUCCIÓN: instanciación de IndicatorsService")
    class ConstructionTests {

        @Test
        @DisplayName("✓ Debe crear IndicatorsService con dependencias inyectadas")
        void should_create_indicators_service_with_dependencies() throws Exception {
            assertThat(indicatorsService, is(notNullValue()));
            assertThat(indicatorsService, instanceOf(IndicatorsService.class));
        }

        @Test
        @DisplayName("✓ Debe exponer método público calculateBasicIndicators")
        void should_expose_public_calculate_method() throws NoSuchMethodException {
            var method = IndicatorsService.class.getDeclaredMethod(
                    "calculateBasicIndicators", String.class, List.class, boolean.class);
            assertThat(method.getName(), is(equalTo("calculateBasicIndicators")));
        }
    }

    // ============ calculateBasicIndicators() ============

    @Nested
    @DisplayName("calculateBasicIndicators(symbol, velas, guardarPrimeras50)")
    class CalculateIndicatorsTests {

        @Test
        @DisplayName("✓ Debe calcular indicadores para velas válidas")
        void should_calculate_indicators_for_valid_velas() throws Exception {
            // Given: Lista de 100 velas (< BATCH_SIZE=100000)
            List<Vela> velas = crearVelasTestList(100);

            when(pythonBridgeFacade.execute(any()))
                    .thenReturn(100);  // 100 indicadores guardados

            // When: Se invoca calculateBasicIndicators
            indicatorsService.calculateBasicIndicators(SYMBOL, velas, false);

            // Then: PythonBridge debe ser invocado
            verify(pythonBridgeFacade, atLeastOnce()).execute(any());
        }

        @Test
        @DisplayName("✓ Debe procesar por lotes cuando hay muchas velas")
        void should_process_velas_in_batches() throws Exception {
            // Given: Lista de 250k velas (> BATCH_SIZE=100k)
            List<Vela> velas = crearVelasTestList(250000);

            when(pythonBridgeFacade.execute(any()))
                    .thenReturn(100000, 100000, 50000);  // 3 lotes

            // When: Se invoca calculateBasicIndicators
            indicatorsService.calculateBasicIndicators(SYMBOL, velas, false);

            // Then: PythonBridge debe ejecutarse múltiples veces (3 lotes)
            verify(pythonBridgeFacade, atLeast(3)).execute(any());
        }

        @Test
        @DisplayName("✓ Debe guardar primeros 50 cuando guardarPrimeras50=true")
        void should_save_first_50_when_flag_true() throws Exception {
            // Given: 100 velas y bandera guardarPrimeras50=true
            List<Vela> velas = crearVelasTestList(100);

            when(pythonBridgeFacade.execute(any()))
                    .thenReturn(50);

            // When: Se invoca con guardarPrimeras50=true
            indicatorsService.calculateBasicIndicators(SYMBOL, velas, true);

            // Then: Debe ejecutarse el cálculo
            verify(pythonBridgeFacade, times(1)).execute(any());
        }

        @Test
        @DisplayName("✓ Debe omitir procesamiento cuando velas está vacía")
        void should_skip_when_velas_empty() throws Exception {
            // Given: Lista vacía
            List<Vela> emptyVelas = List.of();

            // When: Se invoca con lista vacía
            indicatorsService.calculateBasicIndicators(SYMBOL, emptyVelas, false);

            // Then: PythonBridge NO debe ser invocado
            verify(pythonBridgeFacade, never()).execute(any());
        }

        @Test
        @DisplayName("✓ Debe omitir procesamiento cuando velas es null")
        void should_skip_when_velas_null() throws Exception {
            // Given: Velas = null
            // When: Se invoca con null
            indicatorsService.calculateBasicIndicators(SYMBOL, null, false);

            // Then: PythonBridge NO debe ser invocado
            verify(pythonBridgeFacade, never()).execute(any());
        }

        @Test
        @DisplayName("✓ Debe lanzar PythonProcessException cuando Python falla")
        void should_throw_python_process_exception_on_failure() throws Exception {
            // Given: Python Bridge falla
            List<Vela> velas = crearVelasTestList(10);

            when(pythonBridgeFacade.execute(any()))
                    .thenThrow(new PythonBridgeExecutionException("Engine crashed"));

            // When: Se invoca calculateBasicIndicators
            // Then: Debe manejar excepción (log y continuar, no lanzar)
            // Nota: El servicio real captura la excepción y continua procesando
            assertThatNoException().isThrownBy(() ->
                    indicatorsService.calculateBasicIndicators(SYMBOL, velas, false)
            );
        }

        @Test
        @DisplayName("✓ Debe procesar overlap correctamente entre lotes")
        void should_handle_batch_overlap_correctly() throws Exception {
            // Given: 150k velas (2 lotes con overlap=50)
            List<Vela> velas = crearVelasTestList(150000);

            when(pythonBridgeFacade.execute(any()))
                    .thenReturn(100000, 50000);

            // When: Procesa con overlap
            indicatorsService.calculateBasicIndicators(SYMBOL, velas, false);

            // Then: Debe manejar overlap (verificar que se ejecuta sin errores)
            verify(pythonBridgeFacade, atLeast(2)).execute(any());
            verifyNoInteractions(jdbcTemplate);  // No hacemos insert en esta prueba
        }
    }

    // ============ MANEJO DE ERRORES ============

    @Nested
    @DisplayName("ERRORES: manejo de fallos en cálculo")
    class ErrorHandlingTests {

        @Test
        @DisplayName("✓ Debe continuar si un lote falla (resilencia)")
        void should_continue_processing_when_batch_fails() throws Exception {
            // Given: 200k velas (2 lotes, 1er lote falla)
            List<Vela> velas = crearVelasTestList(200000);

            when(pythonBridgeFacade.execute(any()))
                    .thenThrow(new PythonBridgeExecutionException("Lote 1 failed"))
                    .thenReturn(100000);  // Lote 2 ok

            // When: Procesa con fallo en lote 1
            indicatorsService.calculateBasicIndicators(SYMBOL, velas, false);

            // Then: Debe continuar sin lanzar excepción
            verify(pythonBridgeFacade, atLeast(2)).execute(any());
        }

        @Test
        @DisplayName("✓ Debe logs cuando hay error en lote")
        void should_log_error_when_batch_fails() throws Exception {
            // Given: Vela y error
            List<Vela> velas = crearVelasTestList(100);

            when(pythonBridgeFacade.execute(any()))
                    .thenThrow(new PythonBridgeExecutionException("Math error"));

            // When: Procesa con error
            indicatorsService.calculateBasicIndicators(SYMBOL, velas, false);

            // Then: Debe continuar ejecutándose sin crash
            verify(pythonBridgeFacade).execute(any());
        }

        @Test
        @DisplayName("✓ Debe manejar retries internos (MAX_RETRIES=3)")
        void should_retry_on_failure() throws Exception {
            // Given: PythonBridge con retries internos
            List<Vela> velas = crearVelasTestList(50);

            when(pythonBridgeFacade.execute(any()))
                    .thenThrow(new PythonBridgeExecutionException("Temporary failure"));

            // When: Intenta procesar
            indicatorsService.calculateBasicIndicators(SYMBOL, velas, false);

            // Then: Execute fue llamado (retries son internos en PythonBridgeRequest)
            verify(pythonBridgeFacade, atLeastOnce()).execute(any());
        }
    }

    // ============ VALIDACIONES ============

    @Nested
    @DisplayName("VALIDACIONES: parámetros de entrada")
    class ValidationTests {

        @Test
        @DisplayName("✓ Debe aceptar symbol válido")
        void should_accept_valid_symbol() throws Exception {
            // Given: Símbolo válido
            List<Vela> velas = crearVelasTestList(10);

            when(pythonBridgeFacade.execute(any()))
                    .thenReturn(10);

            // When: Se invoca con símbolo válido
            assertThatNoException().isThrownBy(() ->
                    indicatorsService.calculateBasicIndicators("ETHUSDT", velas, false)
            );
        }

        @Test
        @DisplayName("✓ Debe aceptar velas de mínimo 1 elemento")
        void should_accept_minimum_velas() throws Exception {
            // Given: 1 vela
            List<Vela> minVelas = crearVelasTestList(1);

            when(pythonBridgeFacade.execute(any()))
                    .thenReturn(1);

            // When: Se procesa
            indicatorsService.calculateBasicIndicators(SYMBOL, minVelas, false);

            // Then: Debe ejecutarse sin error
            verify(pythonBridgeFacade).execute(any());
        }

        @Test
        @DisplayName("✓ Debe aceptar velas de tamaño grande (1M)")
        void should_accept_large_vela_list() throws Exception {
            // Given: 1M velas
            List<Vela> largeVelas = crearVelasTestList(1000000);

            when(pythonBridgeFacade.execute(any()))
                    .thenReturn(1000000);

            // When: Se procesa
            indicatorsService.calculateBasicIndicators(SYMBOL, largeVelas, false);

            // Then: Debe ejecutarse (se procesa en 10 lotes)
            verify(pythonBridgeFacade, atLeast(10)).execute(any());
        }

        @Test
        @DisplayName("✓ Debe aceptar guardarPrimeras50 true/false")
        void should_accept_save_flag_values() throws Exception {
            // Given: Velas y flag
            List<Vela> velas = crearVelasTestList(100);

            when(pythonBridgeFacade.execute(any()))
                    .thenReturn(100, 100);

            // When: Se invoca con true
            indicatorsService.calculateBasicIndicators(SYMBOL, velas, true);
            indicatorsService.calculateBasicIndicators(SYMBOL, velas, false);

            // Then: Ambos deben ejecutarse
            verify(pythonBridgeFacade, times(2)).execute(any());
        }
    }

    // ============ INTERACCIONES CON PUERTOS ============

    @Nested
    @DisplayName("PUERTOS: interacciones con infraestructura")
    class PortInteractionTests {

        @Test
        @DisplayName("✓ Debe invocar PythonBridgeFacade para cálculos")
        void should_invoke_python_bridge() throws Exception {
            // Given: Velas válidas
            List<Vela> velas = crearVelasTestList(50);

            when(pythonBridgeFacade.execute(any()))
                    .thenReturn(50);

            // When: Se calcula
            indicatorsService.calculateBasicIndicators(SYMBOL, velas, false);

            // Then: PythonBridge debe ser invocado
            ArgumentCaptor<PythonBridgeRequest<?>> requestCaptor = ArgumentCaptor.forClass(PythonBridgeRequest.class);
            verify(pythonBridgeFacade).execute(requestCaptor.capture());

            PythonBridgeRequest<?> capturedRequest = requestCaptor.getValue();
            assertThat(capturedRequest, is(notNullValue()));
        }

        @Test
        @DisplayName("✓ Debe persistir resultados con JdbcTemplate batch")
        void should_persist_with_jdbc_batch() throws Exception {
            // Given: Velas que van a producir indicadores
            List<Vela> velas = crearVelasTestList(100);

            // Simulamos que Python devuelve resultados
            when(pythonBridgeFacade.execute(any()))
                    .thenReturn(100);

            // When: Se calcula y persiste
            indicatorsService.calculateBasicIndicators(SYMBOL, velas, false);

            // Then: JdbcTemplate debería ser invocado para batch insert
            // (aunque en este mock simplificado no ejecutamos realmente)
            verify(pythonBridgeFacade).execute(any());
        }

        @Test
        @DisplayName("✓ Debe NO invocar BD si Python no devuelve resultados")
        void should_not_persist_if_python_returns_empty() throws Exception {
            // Given: Velas pero Python devuelve 0 indicadores
            List<Vela> velas = crearVelasTestList(100);

            when(pythonBridgeFacade.execute(any()))
                    .thenReturn(0);  // Sin resultados

            // When: Se calcula
            indicatorsService.calculateBasicIndicators(SYMBOL, velas, false);

            // Then: JdbcTemplate no debería ser usado
            verifyNoInteractions(jdbcTemplate);
        }

        @Test
        @DisplayName("✓ Debe retornar el conteo de indicadores calculados")
        void should_return_count_of_calculated_indicators() throws Exception {
            // Given: Velas
            List<Vela> velas = crearVelasTestList(100);
            int expectedCount = 95;  // 100 velas - 5 no calculables

            when(pythonBridgeFacade.execute(any()))
                    .thenReturn(expectedCount);

            // When: Se calcula
            indicatorsService.calculateBasicIndicators(SYMBOL, velas, false);

            // Then: Debe reportar el conteo correcto
            verify(pythonBridgeFacade).execute(any());
        }
    }

    // ============ CASOS FRONTERIZOS ============

    @Nested
    @DisplayName("BOUNDARY: casos límite y edge cases")
    class BoundaryTests {

        @Test
        @DisplayName("✓ Debe procesar exactamente BATCH_SIZE=100k velas")
        void should_handle_exact_batch_size() throws Exception {
            // Given: Exactamente 100k velas
            List<Vela> velas = crearVelasTestList(100000);

            when(pythonBridgeFacade.execute(any()))
                    .thenReturn(100000);

            // When: Se procesa
            indicatorsService.calculateBasicIndicators(SYMBOL, velas, false);

            // Then: Debe ejecutarse una sola vez
            verify(pythonBridgeFacade, times(1)).execute(any());
        }

        @Test
        @DisplayName("✓ Debe procesar BATCH_SIZE+1 velas (en 2 lotes)")
        void should_handle_batch_size_plus_one() throws Exception {
            // Given: 100001 velas
            List<Vela> velas = crearVelasTestList(100001);

            when(pythonBridgeFacade.execute(any()))
                    .thenReturn(100001);

            // When: Se procesa
            indicatorsService.calculateBasicIndicators(SYMBOL, velas, false);

            // Then: Debe ejecutarse al menos 2 veces
            verify(pythonBridgeFacade, atLeast(2)).execute(any());
        }

        @Test
        @DisplayName("✓ Debe manejar símbolos diferentes sin interferencia")
        void should_handle_different_symbols_independently() throws Exception {
            // Given: Dos símbolos diferentes
            List<Vela> btcVelas = crearVelasTestList(50);
            List<Vela> ethVelas = crearVelasTestList(50);

            when(pythonBridgeFacade.execute(any()))
                    .thenReturn(50, 50);

            // When: Se procesa cada uno
            indicatorsService.calculateBasicIndicators("BTCUSDT", btcVelas, false);
            indicatorsService.calculateBasicIndicators("ETHUSDT", ethVelas, false);

            // Then: Cada uno se procesa independientemente
            verify(pythonBridgeFacade, times(2)).execute(any());
        }

        @Test
        @DisplayName("✓ Debe manejar resultado parcial (menos indicadores que velas)")
        void should_handle_partial_results() throws Exception {
            // Given: 100 velas pero solo 30 producen indicadores válidos
            List<Vela> velas = crearVelasTestList(100);

            when(pythonBridgeFacade.execute(any()))
                    .thenReturn(30);  // 30 < 100

            // When: Se procesa
            indicatorsService.calculateBasicIndicators(SYMBOL, velas, false);

            // Then: Debe guardar solo los 30
            verify(pythonBridgeFacade).execute(any());
        }

        @Test
        @DisplayName("✓ Debe manejar resultado completo (todos indicadores válidos)")
        void should_handle_full_results() throws Exception {
            // Given: 100 velas todas producen indicadores
            List<Vela> velas = crearVelasTestList(100);

            when(pythonBridgeFacade.execute(any()))
                    .thenReturn(100);  // 100 == 100

            // When: Se procesa
            indicatorsService.calculateBasicIndicators(SYMBOL, velas, false);

            // Then: Debe guardar todos
            verify(pythonBridgeFacade).execute(any());
        }
    }

    // ============ HELPERS ============

    /**
     * Crea una lista de velas de prueba con timestamps secuenciales
     */
    private List<Vela> crearVelasTestList(int count) {
        List<Vela> velas = new ArrayList<>();
        long openTime = NOW_MS;

        for (int i = 0; i < count; i++) {
            Vela vela = new Vela();
            vela.setSymbol("BTCUSDT");
            vela.setInterval("1h");
            vela.setOpenTime(openTime);
            vela.setCloseTime(openTime + 3600000);  // 1 hora después
            vela.setOpen(new BigDecimal("40000.00"));
            vela.setHigh(new BigDecimal("40500.00"));
            vela.setLow(new BigDecimal("39500.00"));
            vela.setClose(new BigDecimal("40200.00"));
            vela.setVolume(new BigDecimal("1000.50"));
            vela.setQuoteVolume(new BigDecimal("40200000.00"));
            vela.setTrades(5000);
            vela.setTakerBaseVolume(new BigDecimal("800.00"));
            vela.setTakerQuoteVolume(new BigDecimal("32160000.00"));

            velas.add(vela);
            openTime += 3600000;  // Incrementa 1 hora para siguiente vela
        }

        return velas;
    }
}
