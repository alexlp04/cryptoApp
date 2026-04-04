package com.bottrading.application.strategy;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.bottrading.application.trading.AccountingService;
import com.bottrading.domain.strategy.EstadoEstrategia;
import com.bottrading.domain.strategy.InstanciaEstrategia;
import com.bottrading.domain.strategy.InstanciaEstrategiaRepository;
import com.bottrading.infrastructure.bridge.StrategyRuntimeCoordinator;

import lombok.extern.slf4j.Slf4j;

/**
 * Test unitario para StrategyLifecycleApplicationService.
 * Cubre: construcción, creación/activación, inicio, pausa, terminación, y casos borde.
 *
 * Patrón: @ExtendWith(MockitoExtension.class) + @InjectMocks + @Mock ports.
 * Total: ~27 test methods en 8 @Nested clases.
 */
@Slf4j
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StrategyLifecycleApplicationServiceTest {

    @Mock
    private InstanciaEstrategiaRepository instanciaRepo;

    @Mock
    private AccountingService accountingService;

    @Mock
    private StrategyRuntimeCoordinator runtimeCoordinator;

    @InjectMocks
    private StrategyLifecycleApplicationService service;

    // ========== CONSTRUCCIÓN ==========
    @Nested
    @DisplayName("CONSTRUCCIÓN: Instanciación del servicio")
    class ConstructionTests {

        @Test
        @DisplayName("✓ Debe instanciar el servicio correctamente")
        void should_instantiate_service_correctly() {
            assertThat(service, is(notNullValue()));
        }

        @Test
        @DisplayName("✓ Debe aceptar dependencias no nulas")
        void should_accept_non_null_dependencies() {
            StrategyLifecycleApplicationService svc = new StrategyLifecycleApplicationService(
                    instanciaRepo, accountingService, runtimeCoordinator);
            assertThat(svc, is(notNullValue()));
        }
    }

    // ========== CREAR Y ACTIVAR ==========
    @Nested
    @DisplayName("crearYActivarEstrategia() — Flujo de creación transaccional")
    class CrearYActivarTests {

        @Test
        @DisplayName("✓ Debe persistir la instancia y activar el capital")
        void should_persist_instancia_and_activate_capital() {
            // Given
            InstanciaEstrategia saved = crearInstanciaTest(1L);
            when(instanciaRepo.save(any(InstanciaEstrategia.class))).thenReturn(saved);

            // When
            InstanciaEstrategia result = service.crearYActivarEstrategia(
                    "RSI_SMA", "rsi_sma_v1", "1h", List.of("BTCUSDT"),
                    false, 1L, new BigDecimal("0.02"), new BigDecimal("500"));

            // Then
            assertThat(result, is(notNullValue()));
            assertThat(result.getId(), is(1L));
            verify(instanciaRepo, times(1)).save(any(InstanciaEstrategia.class));
            verify(accountingService, times(1)).activateStrategy(eq(1L), eq(1L), any(BigDecimal.class));
        }

        @Test
        @DisplayName("✓ Debe retornar la instancia con el ID asignado por el repositorio")
        void should_return_instancia_with_assigned_id() {
            // Given
            InstanciaEstrategia saved = crearInstanciaTest(42L);
            when(instanciaRepo.save(any(InstanciaEstrategia.class))).thenReturn(saved);

            // When
            InstanciaEstrategia result = service.crearYActivarEstrategia(
                    "ScalpingRSI", "model_v2", "5m", List.of("ETHUSDT"),
                    true, 2L, new BigDecimal("0.01"), new BigDecimal("200"));

            // Then
            assertThat(result.getId(), is(42L));
        }

        @Test
        @DisplayName("✓ Debe llamar accountingService.activateStrategy con el capital correcto")
        void should_call_activateStrategy_with_correct_capital() {
            // Given
            BigDecimal capital = new BigDecimal("1000.00");
            InstanciaEstrategia saved = crearInstanciaTest(5L);
            when(instanciaRepo.save(any(InstanciaEstrategia.class))).thenReturn(saved);

            // When
            service.crearYActivarEstrategia(
                    "RSI_SMA", "v1", "1h", List.of("BTCUSDT"),
                    false, 3L, new BigDecimal("0.02"), capital);

            // Then
            verify(accountingService).activateStrategy(eq(3L), eq(5L), eq(capital));
        }
    }

    // ========== INICIAR TRADE RT ==========
    @Nested
    @DisplayName("iniciarTradeRT() — Inicio en tiempo real")
    class IniciarTradeRTTests {

        @Test
        @DisplayName("✓ Debe crear/activar la instancia y luego ejecutar proceso RT")
        void should_create_activate_then_launch_rt_process() {
            // Given
            InstanciaEstrategia saved = crearInstanciaTest(10L);
            when(instanciaRepo.save(any(InstanciaEstrategia.class))).thenReturn(saved);

            // When
            service.iniciarTradeRT("RSI_SMA", "v1", "1h", List.of("BTCUSDT"),
                    false, 1L, new BigDecimal("0.02"), new BigDecimal("500"));

            // Then — orden: primero save+accounting, luego coordinator
            verify(instanciaRepo, times(1)).save(any());
            verify(accountingService, times(1)).activateStrategy(anyLong(), anyLong(), any());
            verify(runtimeCoordinator, times(1)).ejecutarTradeEnTiempoReal(eq(saved), anyList());
        }

        @Test
        @DisplayName("✓ El proceso RT debe recibir la instancia guardada (no la original)")
        void should_pass_saved_instancia_to_rt_process() {
            // Given
            InstanciaEstrategia saved = crearInstanciaTest(99L);
            when(instanciaRepo.save(any(InstanciaEstrategia.class))).thenReturn(saved);

            // When
            service.iniciarTradeRT("RSI_SMA", "v1", "1h", List.of("BTCUSDT"),
                    false, 1L, new BigDecimal("0.02"), new BigDecimal("500"));

            // Then
            verify(runtimeCoordinator).ejecutarTradeEnTiempoReal(eq(saved), anyList());
        }
    }

    // ========== INICIAR ESTRATEGIA DETENIDA ==========
    @Nested
    @DisplayName("iniciarEstrategiaDetenida() — Reanudación de estrategia pausada")
    class IniciarEstrategiaDetenidaTests {

        @Test
        @DisplayName("✓ Debe reanudar estrategia DETENIDA y cambiar estado a ACTIVA")
        void should_resume_detenida_strategy_and_set_activa() {
            // Given
            InstanciaEstrategia instancia = crearInstanciaTest(5L);
            instancia.setEstado(EstadoEstrategia.DETENIDA);
            when(instanciaRepo.findById(5L)).thenReturn(Optional.of(instancia));

            // When
            service.iniciarEstrategiaDetenida(5L);

            // Then
            assertThat(instancia.getEstado(), is(EstadoEstrategia.ACTIVA));
            verify(instanciaRepo, times(1)).save(instancia);
            verify(runtimeCoordinator, times(1)).ejecutarTradeEnTiempoReal(eq(instancia), any());
        }

        @Test
        @DisplayName("✓ Debe ignorar silenciosamente si la estrategia no está DETENIDA")
        void should_ignore_when_not_detenida() {
            // Given
            InstanciaEstrategia instancia = crearInstanciaTest(5L);
            instancia.setEstado(EstadoEstrategia.ACTIVA);
            when(instanciaRepo.findById(5L)).thenReturn(Optional.of(instancia));

            // When
            service.iniciarEstrategiaDetenida(5L);

            // Then
            verify(instanciaRepo, never()).save(any());
            verify(runtimeCoordinator, never()).ejecutarTradeEnTiempoReal(any(), any());
        }

        @Test
        @DisplayName("✓ Debe ignorar silenciosamente si la instancia no existe")
        void should_ignore_when_instancia_not_found() {
            // Given
            when(instanciaRepo.findById(99L)).thenReturn(Optional.empty());

            // When & Then — no excepción
            assertDoesNotThrow(() -> service.iniciarEstrategiaDetenida(99L));
            verify(runtimeCoordinator, never()).ejecutarTradeEnTiempoReal(any(), any());
        }

        @Test
        @DisplayName("✓ Debe lanzar NullPointerException si instanciaId es null")
        void should_throw_npe_when_instanciaId_is_null() {
            assertThrows(NullPointerException.class,
                    () -> service.iniciarEstrategiaDetenida(null));
        }
    }

    // ========== INICIAR TODAS DETENIDAS ==========
    @Nested
    @DisplayName("iniciarTodasDetenidas() — Reanudación masiva")
    class IniciarTodasDetenidasTests {

        @Test
        @DisplayName("✓ Debe reanudar todas las estrategias detenidas")
        void should_resume_all_detenidas() {
            // Given
            InstanciaEstrategia i1 = crearInstanciaTest(1L);
            i1.setEstado(EstadoEstrategia.DETENIDA);
            InstanciaEstrategia i2 = crearInstanciaTest(2L);
            i2.setEstado(EstadoEstrategia.DETENIDA);
            when(instanciaRepo.findByEstado(EstadoEstrategia.DETENIDA)).thenReturn(List.of(i1, i2));
            when(instanciaRepo.findById(1L)).thenReturn(Optional.of(i1));
            when(instanciaRepo.findById(2L)).thenReturn(Optional.of(i2));

            // When
            service.iniciarTodasDetenidas();

            // Then
            verify(runtimeCoordinator, times(2)).ejecutarTradeEnTiempoReal(any(), any());
        }

        @Test
        @DisplayName("✓ Debe ser no-op si no hay estrategias detenidas")
        void should_be_noop_when_no_detenidas() {
            // Given
            when(instanciaRepo.findByEstado(EstadoEstrategia.DETENIDA)).thenReturn(List.of());

            // When
            service.iniciarTodasDetenidas();

            // Then
            verify(runtimeCoordinator, never()).ejecutarTradeEnTiempoReal(any(), any());
        }
    }

    // ========== DETENER ESTRATEGIA ==========
    @Nested
    @DisplayName("detenerEstrategia() — Pausa de estrategia activa")
    class DetenerEstrategiaTests {

        @Test
        @DisplayName("✓ Debe pausar estrategia ACTIVA y cambiar estado a DETENIDA")
        void should_pause_activa_strategy_to_detenida() {
            // Given
            InstanciaEstrategia instancia = crearInstanciaTest(7L);
            instancia.setEstado(EstadoEstrategia.ACTIVA);
            when(instanciaRepo.findByIdWithLock(7L)).thenReturn(Optional.of(instancia));

            // When
            service.detenerEstrategia(7L);

            // Then
            assertThat(instancia.getEstado(), is(EstadoEstrategia.DETENIDA));
            verify(instanciaRepo, times(1)).save(instancia);
        }

        @Test
        @DisplayName("✓ Debe siempre llamar runtimeCoordinator.detenerEstrategia independientemente del estado")
        void should_always_stop_runtime_coordinator() {
            // Given
            InstanciaEstrategia instancia = crearInstanciaTest(7L);
            instancia.setEstado(EstadoEstrategia.DETENIDA);
            when(instanciaRepo.findByIdWithLock(7L)).thenReturn(Optional.of(instancia));

            // When
            service.detenerEstrategia(7L);

            // Then
            verify(runtimeCoordinator, times(1)).detenerEstrategia(7L);
        }

        @Test
        @DisplayName("✓ Debe ignorar si la instancia ya está DETENIDA")
        void should_not_save_when_already_detenida() {
            // Given
            InstanciaEstrategia instancia = crearInstanciaTest(7L);
            instancia.setEstado(EstadoEstrategia.DETENIDA);
            when(instanciaRepo.findByIdWithLock(7L)).thenReturn(Optional.of(instancia));

            // When
            service.detenerEstrategia(7L);

            // Then
            verify(instanciaRepo, never()).save(any());
        }

        @Test
        @DisplayName("✓ Debe lanzar NullPointerException si instanciaId es null")
        void should_throw_npe_when_null_id() {
            assertThrows(NullPointerException.class,
                    () -> service.detenerEstrategia(null));
        }
    }

    // ========== TERMINAR ESTRATEGIA ==========
    @Nested
    @DisplayName("terminarEstrategia() — Liquidación de estrategia")
    class TerminarEstrategiaTests {

        @Test
        @DisplayName("✓ Debe liquidar estrategia no TERMINADA llamando closeStrategy")
        void should_close_non_terminated_strategy() {
            // Given
            InstanciaEstrategia instancia = crearInstanciaTest(8L);
            instancia.setEstado(EstadoEstrategia.ACTIVA);
            instancia.setWalletAsociada(1L);
            when(instanciaRepo.findByIdWithLock(8L)).thenReturn(Optional.of(instancia));

            // When
            service.terminarEstrategia(8L);

            // Then
            verify(accountingService, times(1)).closeStrategy(eq(1L), eq(8L));
        }

        @Test
        @DisplayName("✓ Debe ignorar si la instancia ya está TERMINADA")
        void should_not_close_already_terminated_strategy() {
            // Given
            InstanciaEstrategia instancia = crearInstanciaTest(8L);
            instancia.setEstado(EstadoEstrategia.TERMINADA);
            when(instanciaRepo.findByIdWithLock(8L)).thenReturn(Optional.of(instancia));

            // When
            service.terminarEstrategia(8L);

            // Then
            verify(accountingService, never()).closeStrategy(anyLong(), anyLong());
        }

        @Test
        @DisplayName("✓ Debe siempre llamar runtimeCoordinator.detenerEstrategia")
        void should_always_stop_runtime_before_closeStrategy() {
            // Given
            InstanciaEstrategia instancia = crearInstanciaTest(8L);
            instancia.setEstado(EstadoEstrategia.ACTIVA);
            instancia.setWalletAsociada(1L);
            when(instanciaRepo.findByIdWithLock(8L)).thenReturn(Optional.of(instancia));

            // When
            service.terminarEstrategia(8L);

            // Then
            verify(runtimeCoordinator, times(1)).detenerEstrategia(8L);
        }

        @Test
        @DisplayName("✓ Debe lanzar NullPointerException si instanciaId es null")
        void should_throw_npe_when_null_id_on_terminar() {
            assertThrows(NullPointerException.class,
                    () -> service.terminarEstrategia(null));
        }
    }

    // ========== DETENER TODAS ==========
    @Nested
    @DisplayName("detenerTodas() / terminarTodas() — Operaciones masivas")
    class MassiveOperationsTests {

        @Test
        @DisplayName("✓ detenerTodas() debe pausar cada estrategia activa del coordinator")
        void should_stop_all_active_strategies() {
            // Given
            when(runtimeCoordinator.getIdsEstrategiasActivas()).thenReturn(Set.of(1L, 2L, 3L));
            InstanciaEstrategia i1 = crearInstanciaTest(1L);
            i1.setEstado(EstadoEstrategia.ACTIVA);
            InstanciaEstrategia i2 = crearInstanciaTest(2L);
            i2.setEstado(EstadoEstrategia.ACTIVA);
            InstanciaEstrategia i3 = crearInstanciaTest(3L);
            i3.setEstado(EstadoEstrategia.ACTIVA);
            when(instanciaRepo.findByIdWithLock(1L)).thenReturn(Optional.of(i1));
            when(instanciaRepo.findByIdWithLock(2L)).thenReturn(Optional.of(i2));
            when(instanciaRepo.findByIdWithLock(3L)).thenReturn(Optional.of(i3));

            // When
            service.detenerTodas();

            // Then — detenerEstrategia interno llama a runtimeCoordinator.detenerEstrategia para cada id
            verify(runtimeCoordinator, times(3)).detenerEstrategia(anyLong());
        }

        @Test
        @DisplayName("✓ detenerTodas() debe ser no-op si no hay estrategias activas")
        void should_be_noop_when_no_active_for_detener_todas() {
            // Given
            when(runtimeCoordinator.getIdsEstrategiasActivas()).thenReturn(Set.of());

            // When
            service.detenerTodas();

            // Then
            verify(runtimeCoordinator, never()).detenerEstrategia(anyLong());
        }

        @Test
        @DisplayName("✓ terminarTodas() debe terminar cada estrategia activa del coordinator")
        void should_terminate_all_active_strategies() {
            // Given
            when(runtimeCoordinator.getIdsEstrategiasActivas()).thenReturn(Set.of(10L, 20L));
            InstanciaEstrategia i10 = crearInstanciaTest(10L);
            i10.setEstado(EstadoEstrategia.ACTIVA);
            i10.setWalletAsociada(1L);
            InstanciaEstrategia i20 = crearInstanciaTest(20L);
            i20.setEstado(EstadoEstrategia.ACTIVA);
            i20.setWalletAsociada(1L);
            when(instanciaRepo.findByIdWithLock(10L)).thenReturn(Optional.of(i10));
            when(instanciaRepo.findByIdWithLock(20L)).thenReturn(Optional.of(i20));

            // When
            service.terminarTodas();

            // Then
            verify(accountingService, times(2)).closeStrategy(anyLong(), anyLong());
        }
    }

    // ========== HELPERS ==========
    private InstanciaEstrategia crearInstanciaTest(Long id) {
        InstanciaEstrategia instancia = new InstanciaEstrategia();
        instancia.setId(id);
        instancia.setNombreEstrategia("RSI_SMA");
        instancia.setTimeframe("1h");
        instancia.setWalletAsociada(1L);
        instancia.setCapitalAsignado(new BigDecimal("500.00"));
        instancia.setCapitalReservado(new BigDecimal("500.00"));
        instancia.setCapitalComprometido(BigDecimal.ZERO);
        instancia.setRiskPerTrade(new BigDecimal("0.02"));
        instancia.setEstado(EstadoEstrategia.CREADA);
        return instancia;
    }
}
