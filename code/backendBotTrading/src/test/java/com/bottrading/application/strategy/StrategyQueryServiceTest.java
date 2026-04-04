package com.bottrading.application.strategy;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
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

import com.bottrading.domain.strategy.EstadoEstrategia;
import com.bottrading.domain.strategy.InstanciaEstrategia;
import com.bottrading.domain.strategy.InstanciaEstrategiaRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Test unitario para StrategyQueryService.
 * Cubre: listados, filtrado por estado, mensajes vacíos.
 *
 * Total: ~12 tests en 5 @Nested clases.
 */
@Slf4j
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StrategyQueryServiceTest {

    @Mock
    private InstanciaEstrategiaRepository instanciaRepo;

    @InjectMocks
    private StrategyQueryService service;

    // ========== CONSTRUCCIÓN ==========
    @Nested
    @DisplayName("CONSTRUCCIÓN: Instanciación del servicio")
    class ConstructionTests {

        @Test
        @DisplayName("✓ Debe instanciar el servicio correctamente")
        void should_instantiate_service() {
            assertThat(service, is(notNullValue()));
        }
    }

    // ========== LISTAR TODAS ==========
    @Nested
    @DisplayName("listarEstrategias() — Listado completo")
    class ListarTodasTests {

        @Test
        @DisplayName("✓ Debe retornar representación string de todas las instancias")
        void should_return_string_for_all_instances() {
            // Given
            InstanciaEstrategia i1 = crearInstanciaTest(1L, EstadoEstrategia.ACTIVA);
            InstanciaEstrategia i2 = crearInstanciaTest(2L, EstadoEstrategia.TERMINADA);
            when(instanciaRepo.findAll()).thenReturn(List.of(i1, i2));

            // When
            List<String> result = service.listarEstrategias();

            // Then
            assertThat(result, hasSize(2));
        }

        @Test
        @DisplayName("✓ Debe retornar lista vacía cuando no hay estrategias")
        void should_return_empty_when_no_strategies() {
            // Given
            when(instanciaRepo.findAll()).thenReturn(List.of());

            // When
            List<String> result = service.listarEstrategias();

            // Then
            assertThat(result, is(empty()));
        }
    }

    // ========== LISTAR ACTIVAS ==========
    @Nested
    @DisplayName("listarEstrategiasActivas() — Filtro ACTIVA")
    class ListarActivasTests {

        @Test
        @DisplayName("✓ Debe filtrar con EstadoEstrategia.ACTIVA")
        void should_query_by_activa_estado() {
            // Given
            when(instanciaRepo.findByEstado(EstadoEstrategia.ACTIVA)).thenReturn(List.of());

            // When
            service.listarEstrategiasActivas();

            // Then
            verify(instanciaRepo, times(1)).findByEstado(EstadoEstrategia.ACTIVA);
        }

        @Test
        @DisplayName("✓ Debe retornar mensaje vacío cuando no hay activas")
        void should_return_empty_message_when_no_activas() {
            // Given
            when(instanciaRepo.findByEstado(EstadoEstrategia.ACTIVA)).thenReturn(List.of());

            // When
            List<String> result = service.listarEstrategiasActivas();

            // Then
            assertThat(result, hasSize(1));
            assertThat(result.get(0), containsString("No hay"));
        }

        @Test
        @DisplayName("✓ Debe retornar la representación de las instancias activas")
        void should_return_active_instances() {
            // Given
            InstanciaEstrategia i = crearInstanciaTest(1L, EstadoEstrategia.ACTIVA);
            when(instanciaRepo.findByEstado(EstadoEstrategia.ACTIVA)).thenReturn(List.of(i));

            // When
            List<String> result = service.listarEstrategiasActivas();

            // Then
            assertThat(result, hasSize(1));
        }
    }

    // ========== LISTAR DETENIDAS ==========
    @Nested
    @DisplayName("listarEstrategiasDetenidas() — Filtro DETENIDA")
    class ListarDetenidasTests {

        @Test
        @DisplayName("✓ Debe filtrar con EstadoEstrategia.DETENIDA")
        void should_query_by_detenida_estado() {
            // Given
            when(instanciaRepo.findByEstado(EstadoEstrategia.DETENIDA)).thenReturn(List.of());

            // When
            service.listarEstrategiasDetenidas();

            // Then
            verify(instanciaRepo, times(1)).findByEstado(EstadoEstrategia.DETENIDA);
        }

        @Test
        @DisplayName("✓ Debe retornar mensaje vacío cuando no hay detenidas")
        void should_return_empty_message_when_no_detenidas() {
            // Given
            when(instanciaRepo.findByEstado(EstadoEstrategia.DETENIDA)).thenReturn(List.of());

            // When
            List<String> result = service.listarEstrategiasDetenidas();

            // Then
            assertThat(result, hasSize(1));
            assertThat(result.get(0), containsString("No hay"));
        }
    }

    // ========== LISTAR TERMINADAS ==========
    @Nested
    @DisplayName("listarEstrategiasTerminadas() — Filtro TERMINADA")
    class ListarTerminadasTests {

        @Test
        @DisplayName("✓ Debe filtrar con EstadoEstrategia.TERMINADA")
        void should_query_by_terminada_estado() {
            // Given
            when(instanciaRepo.findByEstado(EstadoEstrategia.TERMINADA)).thenReturn(List.of());

            // When
            service.listarEstrategiasTerminadas();

            // Then
            verify(instanciaRepo, times(1)).findByEstado(EstadoEstrategia.TERMINADA);
        }

        @Test
        @DisplayName("✓ Debe retornar mensaje vacío cuando no hay terminadas")
        void should_return_empty_message_when_no_terminadas() {
            // Given
            when(instanciaRepo.findByEstado(EstadoEstrategia.TERMINADA)).thenReturn(List.of());

            // When
            List<String> result = service.listarEstrategiasTerminadas();

            // Then
            assertThat(result, hasSize(1));
            assertThat(result.get(0), containsString("No hay"));
        }
    }

    // ========== HELPERS ==========
    private InstanciaEstrategia crearInstanciaTest(Long id, EstadoEstrategia estado) {
        InstanciaEstrategia instancia = new InstanciaEstrategia();
        instancia.setId(id);
        instancia.setNombreEstrategia("RSI_SMA");
        instancia.setTimeframe("1h");
        instancia.setWalletAsociada(1L);
        instancia.setCapitalAsignado(new BigDecimal("500.00"));
        instancia.setCapitalReservado(new BigDecimal("500.00"));
        instancia.setCapitalComprometido(BigDecimal.ZERO);
        instancia.setRiskPerTrade(new BigDecimal("0.02"));
        instancia.setEstado(estado);
        return instancia;
    }
}
