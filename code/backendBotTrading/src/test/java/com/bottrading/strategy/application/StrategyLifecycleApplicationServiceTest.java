package com.bottrading.strategy.application;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
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
import com.bottrading.trading.application.port.in.AccountingUseCase;
import com.bottrading.trading.infrastructure.bridge.StrategyRuntimeCoordinator;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StrategyLifecycleApplicationServiceTest {

    @Mock
    private InstanciaEstrategiaRepositoryPort instanciaRepo;

    @Mock
    private AccountingUseCase accountingService;

    @Mock
    private StrategyRuntimeCoordinator runtimeCoordinator;

    @InjectMocks
    private StrategyLifecycleApplicationService lifecycleService;

    // ─────────────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────────────
    private InstanciaEstrategia crearInstanciaTest(Long id, EstadoEstrategia estado) {
        InstanciaEstrategia inst = InstanciaEstrategia.inicializar(
                "RSISMAStrategy", "modelo-test", "1h",
                List.of("BTCUSDT"), false, 1L,
                new BigDecimal("0.01"), new BigDecimal("100"));
        inst.setId(id);
        inst.setEstado(estado);
        inst.setWalletAsociada(1L);
        return inst;
    }

    /** Configura el mock de save para retornar la misma instancia con id asignado. */
    @BeforeEach
    void configurarSaveDefault() {
        when(instanciaRepo.save(any(InstanciaEstrategia.class))).thenAnswer(inv -> {
            InstanciaEstrategia inst = inv.getArgument(0);
            if (inst.getId() == null) {
                inst.setId(42L);
            }
            return inst;
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CONSTRUCCIÓN
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("CONSTRUCCIÓN: Instanciación del servicio")
    class ConstructionTests {

        @Test
        @DisplayName("✓ La instancia no debe ser nula con las dependencias inyectadas")
        void should_create_non_null_instance() {
            assertThat(lifecycleService, is(notNullValue()));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // crearYActivarEstrategia()
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("crearYActivarEstrategia() — Creación y activación financiera")
    class CrearYActivarTests {

        @Test
        @DisplayName("✓ Debe guardar la instancia y activar en contabilidad")
        void should_save_instance_and_activate_accounting() {
            doReturn(crearInstanciaTest(42L, EstadoEstrategia.CREADA))
                    .when(accountingService).activateStrategy(anyLong(), anyLong(), any());

            InstanciaEstrategia result = lifecycleService.crearYActivarEstrategia(
                    "RSISMAStrategy", "modelo", "1h", List.of("BTCUSDT"),
                    false, 1L, new BigDecimal("0.01"), new BigDecimal("100"));

            verify(instanciaRepo, times(1)).save(any(InstanciaEstrategia.class));
            verify(accountingService, times(1)).activateStrategy(eq(1L), eq(42L), any(BigDecimal.class));
            assertThat(result, is(notNullValue()));
        }

        @Test
        @DisplayName("✓ Debe retornar la instancia persistida con id asignado")
        void should_return_persisted_instance_with_id() {
            doReturn(crearInstanciaTest(42L, EstadoEstrategia.CREADA))
                    .when(accountingService).activateStrategy(anyLong(), anyLong(), any());

            InstanciaEstrategia result = lifecycleService.crearYActivarEstrategia(
                    "RSISMAStrategy", "modelo", "1h", List.of("BTCUSDT"),
                    false, 1L, new BigDecimal("0.01"), new BigDecimal("100"));

            assertThat(result.getId(), is(42L));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // iniciarTradeRT()
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("iniciarTradeRT() — Inicio de nueva estrategia en tiempo real")
    class IniciarTradeRtTests {

        @Test
        @DisplayName("✓ Debe crear la instancia y lanzar proceso Python via runtimeCoordinator")
        void should_create_instance_and_launch_rt_process() {
            doReturn(crearInstanciaTest(42L, EstadoEstrategia.ACTIVA))
                    .when(accountingService).activateStrategy(anyLong(), anyLong(), any());

            lifecycleService.iniciarTradeRT(
                    "RSISMAStrategy", "modelo", "1h", List.of("BTCUSDT"),
                    false, 1L, new BigDecimal("0.01"), new BigDecimal("100"));

            verify(instanciaRepo, times(1)).save(any(InstanciaEstrategia.class));
            verify(accountingService, times(1)).activateStrategy(anyLong(), anyLong(), any());
            verify(runtimeCoordinator, times(1)).ejecutarTradeEnTiempoReal(
                    any(InstanciaEstrategia.class), any(List.class));
        }

        @Test
        @DisplayName("✓ Debe pasar la lista de coins al runtimeCoordinator")
        void should_pass_coin_list_to_runtimeCoordinator() {
            List<String> coins = List.of("BTCUSDT", "ETHUSDT");
            doReturn(crearInstanciaTest(42L, EstadoEstrategia.ACTIVA))
                    .when(accountingService).activateStrategy(anyLong(), anyLong(), any());

            lifecycleService.iniciarTradeRT(
                    "RSISMAStrategy", "modelo", "1h", coins,
                    false, 1L, new BigDecimal("0.01"), new BigDecimal("100"));

            verify(runtimeCoordinator, times(1)).ejecutarTradeEnTiempoReal(
                    any(InstanciaEstrategia.class), eq(coins));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // iniciarEstrategiaDetenida()
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("iniciarEstrategiaDetenida() — Reanudación de estrategia pausada")
    class IniciarDetenidasTests {

        @Test
        @DisplayName("✓ Debe cambiar estado a ACTIVA, guardar y lanzar proceso")
        void should_set_activa_save_and_launch_when_detenida() {
            InstanciaEstrategia inst = crearInstanciaTest(10L, EstadoEstrategia.DETENIDA);
            doReturn(Optional.of(inst)).when(instanciaRepo).findById(10L);

            lifecycleService.iniciarEstrategiaDetenida(10L);

            assertThat(inst.getEstado(), is(EstadoEstrategia.ACTIVA));
            verify(instanciaRepo, times(1)).save(inst);
            verify(runtimeCoordinator, times(1)).ejecutarTradeEnTiempoReal(
                    eq(inst), any(List.class));
        }

        @Test
        @DisplayName("✓ No debe hacer nada cuando la instancia no existe")
        void should_do_nothing_when_instance_not_found() {
            doReturn(Optional.empty()).when(instanciaRepo).findById(anyLong());

            lifecycleService.iniciarEstrategiaDetenida(99L);

            verify(instanciaRepo, never()).save(any());
            verify(runtimeCoordinator, never()).ejecutarTradeEnTiempoReal(any(), any());
        }

        @Test
        @DisplayName("✓ No debe hacer nada cuando la instancia está en estado ACTIVA")
        void should_do_nothing_when_state_is_not_detenida_activa() {
            InstanciaEstrategia inst = crearInstanciaTest(5L, EstadoEstrategia.ACTIVA);
            doReturn(Optional.of(inst)).when(instanciaRepo).findById(5L);

            lifecycleService.iniciarEstrategiaDetenida(5L);

            verify(instanciaRepo, never()).save(any());
            verify(runtimeCoordinator, never()).ejecutarTradeEnTiempoReal(any(), any());
        }

        @Test
        @DisplayName("✓ No debe hacer nada cuando la instancia está en estado TERMINADA")
        void should_do_nothing_when_state_is_terminada() {
            InstanciaEstrategia inst = crearInstanciaTest(7L, EstadoEstrategia.TERMINADA);
            doReturn(Optional.of(inst)).when(instanciaRepo).findById(7L);

            lifecycleService.iniciarEstrategiaDetenida(7L);

            verify(instanciaRepo, never()).save(any());
            verify(runtimeCoordinator, never()).ejecutarTradeEnTiempoReal(any(), any());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // iniciarTodasDetenidas()
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("iniciarTodasDetenidas() — Reanudar todas las pausadas")
    class IniciarTodasDetenidasTests {

        @Test
        @DisplayName("✓ Debe reanudar cada instancia detenida encontrada")
        void should_resume_each_detenida_instance() {
            InstanciaEstrategia inst1 = crearInstanciaTest(1L, EstadoEstrategia.DETENIDA);
            InstanciaEstrategia inst2 = crearInstanciaTest(2L, EstadoEstrategia.DETENIDA);
            doReturn(List.of(inst1, inst2))
                    .when(instanciaRepo).findByEstado(EstadoEstrategia.DETENIDA);
            doReturn(Optional.of(inst1)).when(instanciaRepo).findById(1L);
            doReturn(Optional.of(inst2)).when(instanciaRepo).findById(2L);

            lifecycleService.iniciarTodasDetenidas();

            verify(instanciaRepo, times(2)).save(any(InstanciaEstrategia.class));
            verify(runtimeCoordinator, times(2)).ejecutarTradeEnTiempoReal(any(), any());
        }

        @Test
        @DisplayName("✓ No debe hacer nada cuando no hay instancias detenidas")
        void should_do_nothing_when_no_detenidas() {
            doReturn(List.of()).when(instanciaRepo).findByEstado(EstadoEstrategia.DETENIDA);

            lifecycleService.iniciarTodasDetenidas();

            verify(instanciaRepo, never()).save(any());
            verify(runtimeCoordinator, never()).ejecutarTradeEnTiempoReal(any(), any());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // detenerEstrategia()
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("detenerEstrategia() — Pausar estrategia activa")
    class DetenerEstrategiaTests {

        @Test
        @DisplayName("✓ Debe detener proceso Python y cambiar estado a DETENIDA")
        void should_stop_python_process_and_set_detenida() {
            InstanciaEstrategia inst = crearInstanciaTest(20L, EstadoEstrategia.ACTIVA);
            doReturn(Optional.of(inst)).when(instanciaRepo).findByIdWithLock(20L);

            lifecycleService.detenerEstrategia(20L);

            verify(runtimeCoordinator, times(1)).detenerEstrategia(20L);
            assertThat(inst.getEstado(), is(EstadoEstrategia.DETENIDA));
            verify(instanciaRepo, times(1)).save(inst);
        }

        @Test
        @DisplayName("✓ No debe guardar si la instancia no está en estado ACTIVA")
        void should_not_save_when_not_activa() {
            InstanciaEstrategia inst = crearInstanciaTest(20L, EstadoEstrategia.DETENIDA);
            doReturn(Optional.of(inst)).when(instanciaRepo).findByIdWithLock(20L);

            lifecycleService.detenerEstrategia(20L);

            verify(runtimeCoordinator, times(1)).detenerEstrategia(20L);
            verify(instanciaRepo, never()).save(any());
        }

        @Test
        @DisplayName("✓ No debe guardar si la instancia no existe con lock")
        void should_not_save_when_instance_not_found_with_lock() {
            doReturn(Optional.empty()).when(instanciaRepo).findByIdWithLock(anyLong());

            lifecycleService.detenerEstrategia(99L);

            verify(runtimeCoordinator, times(1)).detenerEstrategia(99L);
            verify(instanciaRepo, never()).save(any());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // detenerTodas()
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("detenerTodas() — Pausar todas las estrategias activas")
    class DetenerTodasTests {

        @Test
        @DisplayName("✓ Debe detener cada estrategia activa retornada por runtimeCoordinator")
        void should_stop_each_active_strategy() {
            InstanciaEstrategia inst1 = crearInstanciaTest(1L, EstadoEstrategia.ACTIVA);
            InstanciaEstrategia inst2 = crearInstanciaTest(2L, EstadoEstrategia.ACTIVA);
            doReturn(Set.of(1L, 2L)).when(runtimeCoordinator).getIdsEstrategiasActivas();
            doReturn(Optional.of(inst1)).when(instanciaRepo).findByIdWithLock(1L);
            doReturn(Optional.of(inst2)).when(instanciaRepo).findByIdWithLock(2L);

            lifecycleService.detenerTodas();

            verify(runtimeCoordinator, times(1)).detenerEstrategia(1L);
            verify(runtimeCoordinator, times(1)).detenerEstrategia(2L);
            verify(instanciaRepo, times(2)).save(any(InstanciaEstrategia.class));
        }

        @Test
        @DisplayName("✓ No debe hacer nada cuando no hay estrategias activas")
        void should_do_nothing_when_no_active_strategies() {
            doReturn(Set.of()).when(runtimeCoordinator).getIdsEstrategiasActivas();

            lifecycleService.detenerTodas();

            verify(runtimeCoordinator, never()).detenerEstrategia(anyLong());
            verify(instanciaRepo, never()).save(any());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // terminarEstrategia()
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("terminarEstrategia() — Liquidar y cerrar estrategia")
    class TerminarEstrategiaTests {

        @Test
        @DisplayName("✓ Debe detener proceso y cerrar contabilidad cuando no está TERMINADA")
        void should_stop_process_and_close_accounting_when_not_terminada() {
            InstanciaEstrategia inst = crearInstanciaTest(30L, EstadoEstrategia.ACTIVA);
            doReturn(Optional.of(inst)).when(instanciaRepo).findByIdWithLock(30L);

            lifecycleService.terminarEstrategia(30L);

            verify(runtimeCoordinator, times(1)).detenerEstrategia(30L);
            verify(accountingService, times(1)).closeStrategy(
                    eq(inst.getWalletAsociada()), eq(30L));
        }

        @Test
        @DisplayName("✓ No debe cerrar contabilidad si la instancia ya está TERMINADA")
        void should_not_close_accounting_when_already_terminada() {
            InstanciaEstrategia inst = crearInstanciaTest(30L, EstadoEstrategia.TERMINADA);
            doReturn(Optional.of(inst)).when(instanciaRepo).findByIdWithLock(30L);

            lifecycleService.terminarEstrategia(30L);

            verify(runtimeCoordinator, times(1)).detenerEstrategia(30L);
            verify(accountingService, never()).closeStrategy(anyLong(), anyLong());
        }

        @Test
        @DisplayName("✓ No debe cerrar contabilidad si la instancia no existe con lock")
        void should_not_close_accounting_when_not_found() {
            doReturn(Optional.empty()).when(instanciaRepo).findByIdWithLock(anyLong());

            lifecycleService.terminarEstrategia(99L);

            verify(runtimeCoordinator, times(1)).detenerEstrategia(99L);
            verify(accountingService, never()).closeStrategy(anyLong(), anyLong());
        }

        @Test
        @DisplayName("✓ Debe terminar instancias detenidas también (no solo activas)")
        void should_terminate_detenida_instances_too() {
            InstanciaEstrategia inst = crearInstanciaTest(31L, EstadoEstrategia.DETENIDA);
            doReturn(Optional.of(inst)).when(instanciaRepo).findByIdWithLock(31L);

            lifecycleService.terminarEstrategia(31L);

            verify(accountingService, times(1)).closeStrategy(anyLong(), eq(31L));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // terminarTodas()
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("terminarTodas() — Liquidar todas las estrategias activas")
    class TerminarTodasTests {

        @Test
        @DisplayName("✓ Debe terminar cada estrategia activa retornada por runtimeCoordinator")
        void should_terminate_each_active_strategy() {
            InstanciaEstrategia inst1 = crearInstanciaTest(1L, EstadoEstrategia.ACTIVA);
            InstanciaEstrategia inst2 = crearInstanciaTest(2L, EstadoEstrategia.ACTIVA);
            doReturn(Set.of(1L, 2L)).when(runtimeCoordinator).getIdsEstrategiasActivas();
            doReturn(Optional.of(inst1)).when(instanciaRepo).findByIdWithLock(1L);
            doReturn(Optional.of(inst2)).when(instanciaRepo).findByIdWithLock(2L);

            lifecycleService.terminarTodas();

            verify(runtimeCoordinator, times(1)).detenerEstrategia(1L);
            verify(runtimeCoordinator, times(1)).detenerEstrategia(2L);
            verify(accountingService, times(2)).closeStrategy(anyLong(), anyLong());
        }

        @Test
        @DisplayName("✓ No debe hacer nada cuando no hay estrategias activas")
        void should_do_nothing_when_no_active_strategies() {
            doReturn(Set.of()).when(runtimeCoordinator).getIdsEstrategiasActivas();

            lifecycleService.terminarTodas();

            verify(runtimeCoordinator, never()).detenerEstrategia(anyLong());
            verify(accountingService, never()).closeStrategy(anyLong(), anyLong());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CONTRATO DE PUERTOS
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("Contrato de interfaz StrategyLifecycleUseCase")
    class ContratoPuertoEntradaTests {

        @Test
        @DisplayName("✓ StrategyLifecycleApplicationService debe implementar StrategyLifecycleUseCase")
        void should_implement_strategyLifecycleUseCase() {
            assertThat(lifecycleService instanceof
                    com.bottrading.strategy.application.port.in.StrategyLifecycleUseCase,
                    is(true));
        }
    }
}
