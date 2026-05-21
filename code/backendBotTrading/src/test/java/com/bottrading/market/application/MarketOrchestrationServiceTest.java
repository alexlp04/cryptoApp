package com.bottrading.market.application;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

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

import com.bottrading.market.application.port.in.CalculateIndicatorsUseCase;
import com.bottrading.market.application.port.in.FetchMarketDataUseCase;
import com.bottrading.market.application.port.out.VelaRepositoryPort;
import com.bottrading.market.domain.Vela;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MarketOrchestrationServiceTest {

    @Mock
    private VelaRepositoryPort velaRepo;

    @Mock
    private FetchMarketDataUseCase fetchService;

    @Mock
    private CalculateIndicatorsUseCase indicatorsService;

    @InjectMocks
    private MarketOrchestrationService orchestrationService;

    // ─── Constantes de test ──────────────────────────────────────────────────
    private static final String SYMBOL = "BTCUSDT";
    private static final String INTERVAL = "1h";

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

    private List<Vela> crearVelasTestList(int count) {
        List<Vela> velas = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            velas.add(crearVelaTest(SYMBOL));
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
            assertThat(orchestrationService, is(notNullValue()));
        }

        @Test
        @DisplayName("✓ Debe aceptar las tres dependencias por constructor")
        void should_accept_constructor_with_three_dependencies() {
            MarketOrchestrationService sut = new MarketOrchestrationService(
                    velaRepo, fetchService, indicatorsService);
            assertThat(sut, is(notNullValue()));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // actualizarDatosMercado() — DESCARGA PARALELA
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("actualizarDatosMercado() — Descarga paralela de símbolos")
    class ActualizarDatosMercadoTests {

        @Test
        @DisplayName("✓ Debe abortar sin llamar fetch cuando la lista de símbolos es null")
        void should_abort_when_symbols_is_null() {
            orchestrationService.actualizarDatosMercado(null, INTERVAL);

            verifyNoInteractions(fetchService);
        }

        @Test
        @DisplayName("✓ Debe abortar sin llamar fetch cuando la lista de símbolos está vacía")
        void should_abort_when_symbols_is_empty() {
            orchestrationService.actualizarDatosMercado(Collections.emptyList(), INTERVAL);

            verifyNoInteractions(fetchService);
        }

        @Test
        @DisplayName("✓ Debe llamar fetch una vez para un único símbolo")
        void should_call_fetch_once_for_single_symbol() throws InterruptedException {
            orchestrationService.actualizarDatosMercado(List.of(SYMBOL), INTERVAL);

            // Espera breve para que los virtual threads de invokeAll terminen
            Thread.sleep(200);
            verify(fetchService, times(1)).fetch(SYMBOL, INTERVAL);
        }

        @Test
        @DisplayName("✓ Debe llamar fetch para cada símbolo de la lista")
        void should_call_fetch_for_each_symbol() throws InterruptedException {
            List<String> symbols = List.of("BTCUSDT", "ETHUSDT", "BNBUSDT");

            orchestrationService.actualizarDatosMercado(symbols, INTERVAL);

            Thread.sleep(300);
            verify(fetchService, times(1)).fetch("BTCUSDT", INTERVAL);
            verify(fetchService, times(1)).fetch("ETHUSDT", INTERVAL);
            verify(fetchService, times(1)).fetch("BNBUSDT", INTERVAL);
        }

        @Test
        @DisplayName("✓ Debe pasar el intervalo correcto a fetchService.fetch()")
        void should_pass_correct_interval_to_fetch() throws InterruptedException {
            String interval4h = "4h";
            orchestrationService.actualizarDatosMercado(List.of(SYMBOL), interval4h);

            Thread.sleep(200);
            verify(fetchService, times(1)).fetch(SYMBOL, interval4h);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // calcularIndicadoresParaSimbolo() — FLUJO DE CÁLCULO
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("calcularIndicadoresParaSimbolo() — Flujo de cálculo de indicadores")
    class CalcularIndicadoresTests {

        @Test
        @DisplayName("✓ Debe llamar calculateBasicIndicators con las velas cargadas")
        void should_call_calculateBasicIndicators_with_loaded_velas() {
            List<Vela> velas = crearVelasTestList(5);
            doReturn(velas).when(velaRepo).findBySymbolAndIntervalOrderByOpenTimeAsc(SYMBOL, INTERVAL);

            orchestrationService.calcularIndicadoresParaSimbolo(SYMBOL, INTERVAL);

            verify(indicatorsService, times(1))
                    .calculateBasicIndicators(eq(SYMBOL), eq(velas), eq(true));
        }

        @Test
        @DisplayName("✓ No debe llamar calculateBasicIndicators cuando no hay velas en BD")
        void should_not_calculate_when_no_velas_in_database() {
            doReturn(Collections.emptyList())
                    .when(velaRepo).findBySymbolAndIntervalOrderByOpenTimeAsc(SYMBOL, INTERVAL);

            orchestrationService.calcularIndicadoresParaSimbolo(SYMBOL, INTERVAL);

            verify(indicatorsService, never()).calculateBasicIndicators(anyString(), any(), any(Boolean.class));
        }

        @Test
        @DisplayName("✓ Debe consultar el repositorio con el símbolo e intervalo correctos")
        void should_query_repository_with_correct_symbol_and_interval() {
            doReturn(crearVelasTestList(3))
                    .when(velaRepo).findBySymbolAndIntervalOrderByOpenTimeAsc("ETHUSDT", "4h");
            doReturn(Collections.emptyList())
                    .when(velaRepo).findBySymbolAndIntervalOrderByOpenTimeAsc(anyString(), anyString());

            orchestrationService.calcularIndicadoresParaSimbolo("ETHUSDT", "4h");

            verify(velaRepo, times(1))
                    .findBySymbolAndIntervalOrderByOpenTimeAsc("ETHUSDT", "4h");
        }

        @Test
        @DisplayName("✓ Debe pasar guardarPrimeras50=true a calculateBasicIndicators")
        void should_pass_guardar_primeras50_true() {
            List<Vela> velas = crearVelasTestList(10);
            doReturn(velas).when(velaRepo).findBySymbolAndIntervalOrderByOpenTimeAsc(SYMBOL, INTERVAL);

            orchestrationService.calcularIndicadoresParaSimbolo(SYMBOL, INTERVAL);

            verify(indicatorsService).calculateBasicIndicators(anyString(), any(), eq(true));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // prepararDatosParaEntrenamiento() — PIPELINE COMPLETO
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("prepararDatosParaEntrenamiento() — Pipeline de datos de entrenamiento")
    class PrepararDatosEntrenamientoTests {

        @Test
        @DisplayName("✓ Debe llamar fetchIncremental antes de calcular indicadores")
        void should_call_fetch_incremental_before_calculating_indicators() {
            long now = System.currentTimeMillis();
            int dias = 30;
            long fetchedFrom = now - dias * 24L * 60 * 60 * 1000L;

            doReturn(fetchedFrom).when(fetchService).fetchIncremental(SYMBOL, INTERVAL, dias, now);
            doReturn(crearVelasTestList(5))
                    .when(velaRepo).findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(
                            eq(SYMBOL), eq(INTERVAL), any(Long.class));

            orchestrationService.prepararDatosParaEntrenamiento(SYMBOL, INTERVAL, dias, now);

            verify(fetchService, times(1)).fetchIncremental(SYMBOL, INTERVAL, dias, now);
        }

        @Test
        @DisplayName("✓ Debe llamar calculateBasicIndicators cuando hay velas cargadas")
        void should_call_calculateBasicIndicators_when_velas_are_loaded() {
            long now = System.currentTimeMillis();
            int dias = 7;
            long fetchedFrom = now - dias * 24L * 60 * 60 * 1000L;
            List<Vela> velas = crearVelasTestList(5);

            doReturn(fetchedFrom).when(fetchService).fetchIncremental(SYMBOL, INTERVAL, dias, now);
            doReturn(velas).when(velaRepo)
                    .findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(
                            eq(SYMBOL), eq(INTERVAL), any(Long.class));

            orchestrationService.prepararDatosParaEntrenamiento(SYMBOL, INTERVAL, dias, now);

            verify(indicatorsService, times(1))
                    .calculateBasicIndicators(eq(SYMBOL), eq(velas), any(Boolean.class));
        }

        @Test
        @DisplayName("✓ No debe llamar calculateBasicIndicators cuando no hay velas")
        void should_not_calculate_when_no_velas_available() {
            long now = System.currentTimeMillis();
            int dias = 7;
            long fetchedFrom = now - dias * 24L * 60 * 60 * 1000L;

            doReturn(fetchedFrom).when(fetchService).fetchIncremental(SYMBOL, INTERVAL, dias, now);
            doReturn(Collections.emptyList())
                    .when(velaRepo).findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(
                            eq(SYMBOL), eq(INTERVAL), any(Long.class));

            orchestrationService.prepararDatosParaEntrenamiento(SYMBOL, INTERVAL, dias, now);

            verify(indicatorsService, never()).calculateBasicIndicators(anyString(), any(), any(Boolean.class));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CONTRATO DE PUERTOS
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("Contrato de interfaz MarketDataUseCase")
    class ContratoPuertoEntradaTests {

        @Test
        @DisplayName("✓ MarketOrchestrationService debe implementar MarketDataUseCase")
        void should_implement_marketDataUseCase_interface() {
            assertThat(orchestrationService instanceof
                    com.bottrading.market.application.port.in.MarketDataUseCase,
                    is(true));
        }
    }
}
