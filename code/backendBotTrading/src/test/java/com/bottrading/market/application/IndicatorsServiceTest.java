package com.bottrading.market.application;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import com.bottrading.market.domain.Vela;
import com.bottrading.trading.infrastructure.bridge.PythonBridgeExecutionException;
import com.bottrading.trading.infrastructure.bridge.PythonBridgeFacade;
import com.bottrading.trading.infrastructure.bridge.PythonBridgeRequest;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IndicatorsServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private PythonBridgeFacade pythonBridgeFacade;

    @InjectMocks
    private IndicatorsService indicatorsService;

    // ─── Constantes de test ──────────────────────────────────────────────────
    private static final String SYMBOL = "BTCUSDT";
    private static final String INTERVAL = "1h";

    // ─── Helpers ─────────────────────────────────────────────────────────────
    private Vela crearVelaTest(long id, String symbol) {
        Vela v = new Vela(
                symbol, INTERVAL, 1704067200000L + id * 3_600_000L,
                new BigDecimal("40000.00"), new BigDecimal("40500.00"),
                new BigDecimal("39500.00"), new BigDecimal("40200.00"),
                new BigDecimal("100.0"), 1704070800000L + id * 3_600_000L,
                new BigDecimal("4020000.0"), 150,
                new BigDecimal("50.0"), new BigDecimal("2010000.0"));
        return v;
    }

    private List<Vela> crearVelasTestList(int count) {
        List<Vela> velas = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            velas.add(crearVelaTest(i, SYMBOL));
        }
        return velas;
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
            assertThat(indicatorsService, is(notNullValue()));
        }

        @Test
        @DisplayName("✓ Debe aceptar las dos dependencias por constructor")
        void should_accept_constructor_with_two_dependencies() {
            IndicatorsService sut = new IndicatorsService(jdbcTemplate, pythonBridgeFacade);
            assertThat(sut, is(notNullValue()));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // calculateBasicIndicators() — FLUJO CON LISTA VACÍA
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("calculateBasicIndicators() — Validación de entrada vacía")
    class EntradaVaciaTests {

        @Test
        @DisplayName("✓ Debe abortar sin llamar Python cuando la lista de velas es null")
        void should_abort_when_velas_list_is_null() throws PythonBridgeExecutionException {
            indicatorsService.calculateBasicIndicators(SYMBOL, null, false);

            verify(pythonBridgeFacade, never()).execute(any());
        }

        @Test
        @DisplayName("✓ Debe abortar sin llamar Python cuando la lista de velas está vacía")
        void should_abort_when_velas_list_is_empty() throws PythonBridgeExecutionException {
            indicatorsService.calculateBasicIndicators(SYMBOL, Collections.emptyList(), false);

            verify(pythonBridgeFacade, never()).execute(any());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // calculateBasicIndicators() — FLUJO NORMAL
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("calculateBasicIndicators() — Flujo normal de cálculo")
    class FlujoNormalTests {

        @Test
        @DisplayName("✓ Debe llamar al bridge Python exactamente una vez para un lote pequeño")
        void should_call_python_once_for_small_batch() throws PythonBridgeExecutionException {
            List<Vela> velas = crearVelasTestList(10);
            doReturn(10).when(pythonBridgeFacade).execute(any());

            indicatorsService.calculateBasicIndicators(SYMBOL, velas, false);

            verify(pythonBridgeFacade, times(1)).execute(any(PythonBridgeRequest.class));
        }

        @Test
        @DisplayName("✓ Debe llamar al bridge Python para múltiples lotes cuando la lista supera BATCH_SIZE")
        void should_call_python_multiple_times_for_large_batch() throws PythonBridgeExecutionException {
            // BATCH_SIZE = 100000; creamos 2 lotes
            int batches = 2;
            int velaCount = 100000 * batches + 1;
            List<Vela> muchasVelas = crearVelasTestList(velaCount);
            doReturn(100).when(pythonBridgeFacade).execute(any());

            indicatorsService.calculateBasicIndicators(SYMBOL, muchasVelas, false);

            verify(pythonBridgeFacade, times(batches + 1)).execute(any(PythonBridgeRequest.class));
        }

        @Test
        @DisplayName("✓ Debe continuar con lotes siguientes aunque un lote falle con PythonBridgeExecutionException")
        void should_continue_after_partial_lote_failure() throws PythonBridgeExecutionException {
            // PythonBridgeExecutionException → se wrappea a PythonProcessException que el loop captura
            List<Vela> velas = crearVelasTestList(5);
            doThrow(new PythonBridgeExecutionException("Fallo en Python"))
                    .when(pythonBridgeFacade).execute(any());

            // No debe lanzar excepción al exterior — el loop captura Exception
            indicatorsService.calculateBasicIndicators(SYMBOL, velas, false);

            verify(pythonBridgeFacade, times(1)).execute(any(PythonBridgeRequest.class));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // calculateBasicIndicators() — PARÁMETRO guardarPrimeras50
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("calculateBasicIndicators() — Parámetro guardarPrimeras50")
    class GuardarPrimeras50Tests {

        @Test
        @DisplayName("✓ Debe invocar Python correctamente cuando guardarPrimeras50 es true")
        void should_invoke_python_when_guardar_primeras50_is_true() throws PythonBridgeExecutionException {
            List<Vela> velas = crearVelasTestList(3);
            doReturn(3).when(pythonBridgeFacade).execute(any());

            indicatorsService.calculateBasicIndicators(SYMBOL, velas, true);

            verify(pythonBridgeFacade, times(1)).execute(any(PythonBridgeRequest.class));
        }

        @Test
        @DisplayName("✓ Debe invocar Python correctamente cuando guardarPrimeras50 es false")
        void should_invoke_python_when_guardar_primeras50_is_false() throws PythonBridgeExecutionException {
            List<Vela> velas = crearVelasTestList(3);
            doReturn(3).when(pythonBridgeFacade).execute(any());

            indicatorsService.calculateBasicIndicators(SYMBOL, velas, false);

            verify(pythonBridgeFacade, times(1)).execute(any(PythonBridgeRequest.class));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // calculateBasicIndicators() — DISTINTOS SÍMBOLOS
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("calculateBasicIndicators() — Distintos símbolos y timeframes")
    class DistintosSimbolosTests {

        @Test
        @DisplayName("✓ Debe funcionar correctamente para símbolo ETHUSDT")
        void should_work_for_ethusdt_symbol() throws PythonBridgeExecutionException {
            List<Vela> velas = crearVelasTestList(5);
            doReturn(5).when(pythonBridgeFacade).execute(any());

            indicatorsService.calculateBasicIndicators("ETHUSDT", velas, false);

            verify(pythonBridgeFacade, times(1)).execute(any(PythonBridgeRequest.class));
        }

        @Test
        @DisplayName("✓ Debe enviar exactamente una petición por lote de velas analizadas")
        void should_send_one_request_per_batch() throws PythonBridgeExecutionException {
            List<Vela> velas = crearVelasTestList(200);
            doReturn(200).when(pythonBridgeFacade).execute(any());

            indicatorsService.calculateBasicIndicators(SYMBOL, velas, false);

            verify(pythonBridgeFacade, times(1)).execute(any(PythonBridgeRequest.class));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CONTRATO DE PUERTOS
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("Contrato de interfaz CalculateIndicatorsUseCase")
    class ContratoPuertoEntradaTests {

        @Test
        @DisplayName("✓ IndicatorsService debe implementar CalculateIndicatorsUseCase")
        void should_implement_calculateIndicatorsUseCase_interface() {
            assertThat(indicatorsService instanceof
                    com.bottrading.market.application.port.in.CalculateIndicatorsUseCase,
                    is(true));
        }
    }
}
