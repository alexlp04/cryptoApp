package com.bottrading.strategy.application;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

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

import com.bottrading.strategy.application.port.out.InstanciaEstrategiaRepositoryPort;
import com.bottrading.strategy.domain.EstadoEstrategia;
import com.bottrading.strategy.domain.InstanciaEstrategia;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StrategyQueryServiceTest {

    @Mock
    private InstanciaEstrategiaRepositoryPort instanciaRepo;

    @InjectMocks
    private StrategyQueryService queryService;

    // ─────────────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────────────
    private InstanciaEstrategia crearInstanciaTest(String nombre, EstadoEstrategia estado) {
        InstanciaEstrategia inst = InstanciaEstrategia.inicializar(
                nombre, "modelo-test", "1h",
                List.of("BTCUSDT"), false, 1L,
                new BigDecimal("0.01"), new BigDecimal("100"));
        inst.setEstado(estado);
        return inst;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CONSTRUCCIÓN
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("CONSTRUCCIÓN: Instanciación del servicio")
    class ConstructionTests {

        @Test
        @DisplayName("✓ La instancia no debe ser nula con la dependencia inyectada")
        void should_create_non_null_instance() {
            assertThat(queryService, is(notNullValue()));
        }

        @Test
        @DisplayName("✓ Debe aceptar la dependencia por constructor")
        void should_accept_constructor_dependency() {
            StrategyQueryService sut = new StrategyQueryService(instanciaRepo);
            assertThat(sut, is(notNullValue()));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // listarEstrategias()
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("listarEstrategias() — Listado de todas las estrategias")
    class ListarEstrategiasTests {

        @Test
        @DisplayName("✓ Debe retornar lista de strings con toString de cada instancia")
        void should_return_string_list_from_findAll() {
            InstanciaEstrategia inst1 = crearInstanciaTest("RSISMAStrategy", EstadoEstrategia.ACTIVA);
            InstanciaEstrategia inst2 = crearInstanciaTest("ToggleStrategy", EstadoEstrategia.DETENIDA);
            doReturn(List.of(inst1, inst2)).when(instanciaRepo).findAll();

            List<String> result = queryService.listarEstrategias();

            assertThat(result, hasSize(2));
            assertThat(result.get(0), is(inst1.toString()));
            assertThat(result.get(1), is(inst2.toString()));
            verify(instanciaRepo, times(1)).findAll();
        }

        @Test
        @DisplayName("✓ Debe retornar lista vacía cuando no hay instancias")
        void should_return_empty_list_when_no_instances() {
            doReturn(List.of()).when(instanciaRepo).findAll();

            List<String> result = queryService.listarEstrategias();

            assertThat(result, hasSize(0));
        }

        @Test
        @DisplayName("✓ Debe retornar lista de un elemento con una sola instancia")
        void should_return_single_element_list_when_one_instance() {
            InstanciaEstrategia inst = crearInstanciaTest("RSISMAStrategy", EstadoEstrategia.ACTIVA);
            doReturn(List.of(inst)).when(instanciaRepo).findAll();

            List<String> result = queryService.listarEstrategias();

            assertThat(result, hasSize(1));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // listarEstrategiasActivas()
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("listarEstrategiasActivas() — Listado de estrategias con estado ACTIVA")
    class ListarActivasTests {

        @Test
        @DisplayName("✓ Debe retornar strings de instancias activas cuando existen")
        void should_return_active_instances_when_exist() {
            InstanciaEstrategia inst = crearInstanciaTest("RSISMAStrategy", EstadoEstrategia.ACTIVA);
            doReturn(List.of(inst)).when(instanciaRepo).findByEstado(EstadoEstrategia.ACTIVA);

            List<String> result = queryService.listarEstrategiasActivas();

            assertThat(result, hasSize(1));
            assertThat(result.get(0), is(inst.toString()));
            verify(instanciaRepo, times(1)).findByEstado(EstadoEstrategia.ACTIVA);
        }

        @Test
        @DisplayName("✓ Debe retornar mensaje 'No hay estrategias activas.' cuando la lista está vacía")
        void should_return_no_active_message_when_empty() {
            doReturn(List.of()).when(instanciaRepo).findByEstado(EstadoEstrategia.ACTIVA);

            List<String> result = queryService.listarEstrategiasActivas();

            assertThat(result, contains("No hay estrategias activas."));
        }

        @Test
        @DisplayName("✓ Debe filtrar solo por estado ACTIVA, no DETENIDA")
        void should_query_only_activa_state() {
            doReturn(List.of()).when(instanciaRepo).findByEstado(EstadoEstrategia.ACTIVA);

            queryService.listarEstrategiasActivas();

            verify(instanciaRepo, times(0)).findByEstado(EstadoEstrategia.DETENIDA);
            verify(instanciaRepo, times(1)).findByEstado(EstadoEstrategia.ACTIVA);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // listarEstrategiasDetenidas()
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("listarEstrategiasDetenidas() — Listado de estrategias con estado DETENIDA")
    class ListarDetenidasTests {

        @Test
        @DisplayName("✓ Debe retornar strings de instancias detenidas cuando existen")
        void should_return_paused_instances_when_exist() {
            InstanciaEstrategia inst = crearInstanciaTest("ToggleStrategy", EstadoEstrategia.DETENIDA);
            doReturn(List.of(inst)).when(instanciaRepo).findByEstado(EstadoEstrategia.DETENIDA);

            List<String> result = queryService.listarEstrategiasDetenidas();

            assertThat(result, hasSize(1));
            assertThat(result.get(0), is(inst.toString()));
        }

        @Test
        @DisplayName("✓ Debe retornar mensaje 'No hay estrategias en pausa.' cuando la lista está vacía")
        void should_return_no_paused_message_when_empty() {
            doReturn(List.of()).when(instanciaRepo).findByEstado(EstadoEstrategia.DETENIDA);

            List<String> result = queryService.listarEstrategiasDetenidas();

            assertThat(result, contains("No hay estrategias en pausa."));
        }

        @Test
        @DisplayName("✓ Debe filtrar solo por estado DETENIDA")
        void should_query_only_detenida_state() {
            doReturn(List.of()).when(instanciaRepo).findByEstado(EstadoEstrategia.DETENIDA);

            queryService.listarEstrategiasDetenidas();

            verify(instanciaRepo, times(1)).findByEstado(EstadoEstrategia.DETENIDA);
            verify(instanciaRepo, times(0)).findByEstado(EstadoEstrategia.ACTIVA);
        }

        @Test
        @DisplayName("✓ Debe retornar múltiples instancias cuando hay varias detenidas")
        void should_return_multiple_paused_instances() {
            InstanciaEstrategia inst1 = crearInstanciaTest("RsiStrategy", EstadoEstrategia.DETENIDA);
            InstanciaEstrategia inst2 = crearInstanciaTest("SmaStrategy", EstadoEstrategia.DETENIDA);
            doReturn(List.of(inst1, inst2)).when(instanciaRepo).findByEstado(EstadoEstrategia.DETENIDA);

            List<String> result = queryService.listarEstrategiasDetenidas();

            assertThat(result, hasSize(2));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // listarEstrategiasTerminadas()
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("listarEstrategiasTerminadas() — Listado de estrategias con estado TERMINADA")
    class ListarTerminadasTests {

        @Test
        @DisplayName("✓ Debe retornar strings de instancias terminadas cuando existen")
        void should_return_terminated_instances_when_exist() {
            InstanciaEstrategia inst = crearInstanciaTest("RSISMAStrategy", EstadoEstrategia.TERMINADA);
            doReturn(List.of(inst)).when(instanciaRepo).findByEstado(EstadoEstrategia.TERMINADA);

            List<String> result = queryService.listarEstrategiasTerminadas();

            assertThat(result, hasSize(1));
            assertThat(result.get(0), is(inst.toString()));
        }

        @Test
        @DisplayName("✓ Debe retornar mensaje 'No hay estrategias terminadas.' cuando la lista está vacía")
        void should_return_no_terminated_message_when_empty() {
            doReturn(List.of()).when(instanciaRepo).findByEstado(EstadoEstrategia.TERMINADA);

            List<String> result = queryService.listarEstrategiasTerminadas();

            assertThat(result, contains("No hay estrategias terminadas."));
        }

        @Test
        @DisplayName("✓ Debe filtrar solo por estado TERMINADA")
        void should_query_only_terminada_state() {
            doReturn(List.of()).when(instanciaRepo).findByEstado(EstadoEstrategia.TERMINADA);

            queryService.listarEstrategiasTerminadas();

            verify(instanciaRepo, times(1)).findByEstado(EstadoEstrategia.TERMINADA);
            verify(instanciaRepo, times(0)).findByEstado(EstadoEstrategia.ACTIVA);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CONTRATO DE PUERTOS
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("Contrato de interfaz QueryStrategiesUseCase")
    class ContratoPuertoEntradaTests {

        @Test
        @DisplayName("✓ StrategyQueryService debe implementar QueryStrategiesUseCase")
        void should_implement_queryStrategiesUseCase_interface() {
            assertThat(queryService instanceof
                    com.bottrading.strategy.application.port.in.QueryStrategiesUseCase,
                    is(true));
        }
    }
}
