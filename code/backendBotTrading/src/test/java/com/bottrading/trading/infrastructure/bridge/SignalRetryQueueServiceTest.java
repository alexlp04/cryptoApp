package com.bottrading.trading.infrastructure.bridge;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.bottrading.trading.application.port.in.ProcessSignalUseCase;
import com.bottrading.trading.application.port.out.FailedSignalStorePort;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SignalRetryQueueServiceTest {

    private SignalRetryQueueService retryService;
    private FailedSignalStorePort failedSignalStore;

    @BeforeEach
    void setup() {
        failedSignalStore = mock(FailedSignalStorePort.class);
        retryService = new SignalRetryQueueService(failedSignalStore);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────────────
    private SignalDTO crearSignalTest(String symbol, String action) {
        SignalDTO s = new SignalDTO();
        s.setSymbol(symbol);
        s.setAction(action);
        s.setPrice(new BigDecimal("40000"));
        s.setTimeframe("1h");
        s.setTimestamp(System.currentTimeMillis());
        return s;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CONSTRUCCIÓN
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("CONSTRUCCIÓN: Instanciación del servicio")
    class ConstructionTests {

        @Test
        @DisplayName("✓ La instancia no debe ser nula")
        void should_create_non_null_instance() {
            assertThat(retryService, is(notNullValue()));
        }

        @Test
        @DisplayName("✓ La cola debe estar vacía al inicio")
        void should_start_with_empty_queue() {
            assertThat(retryService.getPendingSignalCount(1L), is(0));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // enqueueFailedSignal()
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("enqueueFailedSignal() — Encolar señales fallidas")
    class EnqueueTests {

        @Test
        @DisplayName("✓ Debe incrementar el tamaño de la cola al encolar una señal")
        void should_increment_queue_size_on_enqueue() {
            retryService.enqueueFailedSignal(1L, crearSignalTest("BTCUSDT", "BUY"));

            assertThat(retryService.getPendingSignalCount(1L), is(1));
        }

        @Test
        @DisplayName("✓ Debe acumular múltiples señales en la cola de la misma estrategia")
        void should_accumulate_multiple_signals() {
            retryService.enqueueFailedSignal(1L, crearSignalTest("BTCUSDT", "BUY"));
            retryService.enqueueFailedSignal(1L, crearSignalTest("ETHUSDT", "SELL"));
            retryService.enqueueFailedSignal(1L, crearSignalTest("BTCUSDT", "SELL"));

            assertThat(retryService.getPendingSignalCount(1L), is(3));
        }

        @Test
        @DisplayName("✓ Debe mantener colas separadas por estrategia")
        void should_keep_separate_queues_per_strategy() {
            retryService.enqueueFailedSignal(1L, crearSignalTest("BTCUSDT", "BUY"));
            retryService.enqueueFailedSignal(2L, crearSignalTest("ETHUSDT", "BUY"));
            retryService.enqueueFailedSignal(2L, crearSignalTest("BNBUSDT", "SELL"));

            assertThat(retryService.getPendingSignalCount(1L), is(1));
            assertThat(retryService.getPendingSignalCount(2L), is(2));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // incrementAndCheckFailureLimit()
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("incrementAndCheckFailureLimit() — Límite de fallos consecutivos")
    class FailureLimitTests {

        @Test
        @DisplayName("✓ Debe retornar false mientras el contador no alcanza el límite")
        void should_return_false_below_limit() {
            assertThat(retryService.incrementAndCheckFailureLimit(1L), is(false)); // 1
            assertThat(retryService.incrementAndCheckFailureLimit(1L), is(false)); // 2
            assertThat(retryService.incrementAndCheckFailureLimit(1L), is(false)); // 3
            assertThat(retryService.incrementAndCheckFailureLimit(1L), is(false)); // 4
        }

        @Test
        @DisplayName("✓ Debe retornar true al alcanzar exactamente el límite máximo (5)")
        void should_return_true_at_limit() {
            for (int i = 0; i < 4; i++) {
                retryService.incrementAndCheckFailureLimit(1L);
            }
            boolean atLimit = retryService.incrementAndCheckFailureLimit(1L); // 5

            assertThat(atLimit, is(true));
        }

        @Test
        @DisplayName("✓ Debe retornar true también por encima del límite")
        void should_return_true_above_limit() {
            for (int i = 0; i < 6; i++) {
                retryService.incrementAndCheckFailureLimit(1L);
            }
            boolean overLimit = retryService.incrementAndCheckFailureLimit(1L); // 7

            assertThat(overLimit, is(true));
        }

        @Test
        @DisplayName("✓ Debe mantener contadores independientes por estrategia")
        void should_track_independent_counters_per_strategy() {
            retryService.incrementAndCheckFailureLimit(1L);
            retryService.incrementAndCheckFailureLimit(1L);
            retryService.incrementAndCheckFailureLimit(1L);

            assertThat(retryService.incrementAndCheckFailureLimit(2L), is(false));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // resetFailureCount()
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("resetFailureCount() — Reinicio de contador de fallos")
    class ResetFailureCountTests {

        @Test
        @DisplayName("✓ Debe reiniciar el contador de forma que el siguiente incremento retorne false")
        void should_reset_counter_so_next_increment_is_false() {
            retryService.incrementAndCheckFailureLimit(1L);
            retryService.incrementAndCheckFailureLimit(1L);
            retryService.incrementAndCheckFailureLimit(1L);
            retryService.incrementAndCheckFailureLimit(1L);

            retryService.resetFailureCount(1L);

            assertThat(retryService.incrementAndCheckFailureLimit(1L), is(false)); // vuelta a 1
        }

        @Test
        @DisplayName("✓ Debe funcionar aunque no se haya incrementado antes")
        void should_work_without_prior_increments() {
            retryService.resetFailureCount(99L);
            assertThat(retryService.incrementAndCheckFailureLimit(99L), is(false));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // getPendingSignalCount()
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("getPendingSignalCount() — Consulta de señales pendientes")
    class PendingCountTests {

        @Test
        @DisplayName("✓ Debe retornar 0 para estrategia sin señales encoladas")
        void should_return_zero_for_unknown_strategy() {
            assertThat(retryService.getPendingSignalCount(999L), is(0));
        }

        @Test
        @DisplayName("✓ Debe retornar el número exacto de señales en cola")
        void should_return_exact_count() {
            retryService.enqueueFailedSignal(5L, crearSignalTest("BTCUSDT", "BUY"));
            retryService.enqueueFailedSignal(5L, crearSignalTest("ETHUSDT", "SELL"));

            assertThat(retryService.getPendingSignalCount(5L), is(2));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // procesarSignalesPendientes()
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("procesarSignalesPendientes() — Ciclo de reintentos")
    class ProcesarSignalesTests {

        @Test
        @DisplayName("✓ No debe hacer nada cuando la cola está vacía")
        void should_do_nothing_when_queue_is_empty() {
            ProcessSignalUseCase mockService = mock(ProcessSignalUseCase.class);

            retryService.procesarSignalesPendientes(1L, mockService);

            verify(mockService, never()).onSignal(anyLong(), any());
        }

        @Test
        @DisplayName("✓ No debe hacer nada cuando no hay cola para la estrategia")
        void should_do_nothing_when_no_queue_for_strategy() {
            ProcessSignalUseCase mockService = mock(ProcessSignalUseCase.class);

            retryService.procesarSignalesPendientes(999L, mockService);

            verify(mockService, never()).onSignal(anyLong(), any());
        }

        @Test
        @DisplayName("✓ Debe procesar cada señal de la cola y vaciarla en caso de éxito")
        void should_process_and_clear_queue_on_success() {
            ProcessSignalUseCase mockService = mock(ProcessSignalUseCase.class);
            retryService.enqueueFailedSignal(1L, crearSignalTest("BTCUSDT", "BUY"));
            retryService.enqueueFailedSignal(1L, crearSignalTest("ETHUSDT", "SELL"));

            retryService.procesarSignalesPendientes(1L, mockService);

            verify(mockService, times(2)).onSignal(anyLong(), any(SignalDTO.class));
            assertThat(retryService.getPendingSignalCount(1L), is(0));
        }

        @Test
        @DisplayName("✓ Debe reencolar señal cuando el reintento falla")
        void should_reenqueue_signal_when_retry_fails() {
            ProcessSignalUseCase mockService = mock(ProcessSignalUseCase.class);
            doThrow(new RuntimeException("Error temporal"))
                    .when(mockService).onSignal(anyLong(), any());

            retryService.enqueueFailedSignal(1L, crearSignalTest("BTCUSDT", "BUY"));

            retryService.procesarSignalesPendientes(1L, mockService);

            // La señal se reencola tras el fallo
            assertThat(retryService.getPendingSignalCount(1L), is(1));
        }

        @Test
        @DisplayName("✓ Debe resetear el contador de fallos tras un reintento exitoso")
        void should_reset_failure_count_after_successful_retry() {
            ProcessSignalUseCase mockService = mock(ProcessSignalUseCase.class);
            retryService.incrementAndCheckFailureLimit(1L);
            retryService.incrementAndCheckFailureLimit(1L);
            retryService.enqueueFailedSignal(1L, crearSignalTest("BTCUSDT", "BUY"));

            retryService.procesarSignalesPendientes(1L, mockService);

            // Después de éxito el contador se reinicia, el siguiente incremento es 1 (false)
            assertThat(retryService.incrementAndCheckFailureLimit(1L), is(false));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // cleanup()
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("cleanup() — Limpieza de datos de estrategia")
    class CleanupTests {

        @Test
        @DisplayName("✓ Debe eliminar la cola de señales para la estrategia")
        void should_remove_signal_queue() {
            retryService.enqueueFailedSignal(1L, crearSignalTest("BTCUSDT", "BUY"));

            retryService.cleanup(1L);

            assertThat(retryService.getPendingSignalCount(1L), is(0));
        }

        @Test
        @DisplayName("✓ Debe reiniciar el contador de fallos al hacer cleanup")
        void should_reset_failure_count_on_cleanup() {
            retryService.incrementAndCheckFailureLimit(1L);
            retryService.incrementAndCheckFailureLimit(1L);
            retryService.incrementAndCheckFailureLimit(1L);

            retryService.cleanup(1L);

            // Tras cleanup el contador está a 0, el siguiente incremento es 1 → false
            assertThat(retryService.incrementAndCheckFailureLimit(1L), is(false));
        }

        @Test
        @DisplayName("✓ El cleanup no debe afectar a otras estrategias")
        void should_not_affect_other_strategies() {
            retryService.enqueueFailedSignal(1L, crearSignalTest("BTCUSDT", "BUY"));
            retryService.enqueueFailedSignal(2L, crearSignalTest("ETHUSDT", "SELL"));

            retryService.cleanup(1L);

            assertThat(retryService.getPendingSignalCount(1L), is(0));
            assertThat(retryService.getPendingSignalCount(2L), is(1));
        }

        @Test
        @DisplayName("✓ El cleanup de una estrategia inexistente no debe lanzar excepción")
        void should_not_throw_when_strategy_not_found() {
            retryService.cleanup(999L);
            assertThat(retryService.getPendingSignalCount(999L), is(0));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Durabilidad (C4) — persistencia y recuperación
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("Durabilidad — persistencia y recuperación de la cola")
    class DurabilidadTests {

        @Test
        @DisplayName("✓ Debe persistir en el store al encolar una señal fallida")
        void should_persist_on_enqueue() {
            SignalDTO signal = crearSignalTest("BTCUSDT", "BUY");

            retryService.enqueueFailedSignal(7L, signal);

            verify(failedSignalStore, times(1)).save(eq(7L), any(SignalDTO.class));
        }

        @Test
        @DisplayName("✓ Debe marcar como procesada en el store tras un reintento exitoso")
        void should_mark_processed_on_successful_retry() {
            ProcessSignalUseCase mockService = mock(ProcessSignalUseCase.class);
            retryService.enqueueFailedSignal(7L, crearSignalTest("BTCUSDT", "BUY"));

            retryService.procesarSignalesPendientes(7L, mockService);

            verify(failedSignalStore, times(1)).markProcessed(eq(7L), any(SignalDTO.class));
        }

        @Test
        @DisplayName("✓ NO debe marcar como procesada si el reintento falla")
        void should_not_mark_processed_when_retry_fails() {
            ProcessSignalUseCase mockService = mock(ProcessSignalUseCase.class);
            doThrow(new RuntimeException("Error temporal")).when(mockService).onSignal(anyLong(), any());
            retryService.enqueueFailedSignal(7L, crearSignalTest("BTCUSDT", "BUY"));

            retryService.procesarSignalesPendientes(7L, mockService);

            verify(failedSignalStore, never()).markProcessed(anyLong(), any());
        }

        @Test
        @DisplayName("✓ Debe borrar del store al hacer cleanup")
        void should_delete_from_store_on_cleanup() {
            retryService.enqueueFailedSignal(7L, crearSignalTest("BTCUSDT", "BUY"));

            retryService.cleanup(7L);

            verify(failedSignalStore, times(1)).deleteAllForInstance(7L);
        }

        @Test
        @DisplayName("✓ Debe recuperar señales persistidas a memoria al arrancar")
        void should_recover_pending_signals_on_startup() {
            when(failedSignalStore.instancesWithPending()).thenReturn(List.of(7L));
            when(failedSignalStore.loadPending(7L)).thenReturn(List.of(
                    crearSignalTest("BTCUSDT", "BUY"),
                    crearSignalTest("ETHUSDT", "SELL")));

            retryService.recuperarPendientes();

            assertThat(retryService.getPendingSignalCount(7L), is(2));
        }

        @Test
        @DisplayName("✓ La recuperación no debe volver a persistir lo ya guardado")
        void should_not_resave_on_recovery() {
            when(failedSignalStore.instancesWithPending()).thenReturn(List.of(7L));
            when(failedSignalStore.loadPending(7L)).thenReturn(List.of(crearSignalTest("BTCUSDT", "BUY")));

            retryService.recuperarPendientes();

            verify(failedSignalStore, never()).save(anyLong(), any());
        }

        @Test
        @DisplayName("✓ instanciasConPendientes solo debe listar estrategias con cola no vacía")
        void should_list_only_instances_with_pending() {
            retryService.enqueueFailedSignal(1L, crearSignalTest("BTCUSDT", "BUY"));
            retryService.enqueueFailedSignal(2L, crearSignalTest("ETHUSDT", "SELL"));
            retryService.cleanup(2L);

            assertThat(retryService.instanciasConPendientes(), is(java.util.Set.of(1L)));
        }
    }
}
