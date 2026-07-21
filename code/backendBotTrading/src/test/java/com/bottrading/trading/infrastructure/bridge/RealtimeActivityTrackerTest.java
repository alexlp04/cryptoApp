package com.bottrading.trading.infrastructure.bridge;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests del watchdog de liveness RT. La hora se inyecta para verificar los
 * timeouts sin depender del reloj real.
 */
@DisplayName("RealtimeActivityTracker — detección de cuelgues RT")
class RealtimeActivityTrackerTest {

    private static final long T0 = 1_000_000L;
    private static final long STARTUP = 90_000L;
    private static final long INACTIVITY = 60_000L;

    private RealtimeActivityTracker newTracker() {
        return new RealtimeActivityTracker(T0, STARTUP, INACTIVITY);
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("CONSTRUCCIÓN")
    class ConstructionTests {

        @Test
        @DisplayName("✓ Debe crear una instancia válida")
        void should_create_non_null_instance() {
            assertThat(newTracker(), is(notNullValue()));
        }

        @Test
        @DisplayName("✓ Debe rechazar timeout de arranque no positivo")
        void should_reject_non_positive_startup() {
            assertThrows(IllegalArgumentException.class,
                    () -> new RealtimeActivityTracker(T0, 0L, INACTIVITY));
        }

        @Test
        @DisplayName("✓ Debe rechazar timeout de inactividad no positivo")
        void should_reject_non_positive_inactivity() {
            assertThrows(IllegalArgumentException.class,
                    () -> new RealtimeActivityTracker(T0, STARTUP, -1L));
        }

        @Test
        @DisplayName("✓ Recién creado no debe tener actividad previa")
        void should_start_without_activity() {
            assertThat(newTracker().hasFirstActivity(), is(false));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("TIMEOUT DE ARRANQUE — sin ninguna salida")
    class StartupTimeoutTests {

        @Test
        @DisplayName("✓ No debe disparar antes del plazo de arranque")
        void should_not_timeout_before_startup_deadline() {
            RealtimeActivityTracker tracker = newTracker();
            assertThat(tracker.isStartupTimedOut(T0 + STARTUP), is(false));
        }

        @Test
        @DisplayName("✓ Debe disparar tras superar el plazo de arranque sin salida")
        void should_timeout_after_startup_deadline() {
            RealtimeActivityTracker tracker = newTracker();
            assertThat(tracker.isStartupTimedOut(T0 + STARTUP + 1), is(true));
            assertThat(tracker.timedOutReason(T0 + STARTUP + 1), containsString("arranque"));
        }

        @Test
        @DisplayName("✓ No debe considerarse timeout de arranque si ya hubo actividad")
        void should_not_startup_timeout_once_active() {
            RealtimeActivityTracker tracker = newTracker();
            tracker.markActivity(T0 + 1_000);
            assertThat(tracker.isStartupTimedOut(T0 + STARTUP + 10_000), is(false));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("TIMEOUT DE INACTIVIDAD — silencio tras arrancar")
    class InactivityTimeoutTests {

        @Test
        @DisplayName("✓ No debe disparar inactividad si nunca hubo actividad")
        void should_not_inactivity_timeout_without_first_activity() {
            RealtimeActivityTracker tracker = newTracker();
            assertThat(tracker.isInactivityTimedOut(T0 + INACTIVITY + 10_000), is(false));
        }

        @Test
        @DisplayName("✓ No debe disparar justo en el límite de inactividad")
        void should_not_timeout_at_exact_limit() {
            RealtimeActivityTracker tracker = newTracker();
            tracker.markActivity(T0);
            assertThat(tracker.isInactivityTimedOut(T0 + INACTIVITY), is(false));
        }

        @Test
        @DisplayName("✓ Debe disparar tras superar el silencio máximo")
        void should_timeout_after_silence() {
            RealtimeActivityTracker tracker = newTracker();
            tracker.markActivity(T0);
            assertThat(tracker.isInactivityTimedOut(T0 + INACTIVITY + 1), is(true));
            assertThat(tracker.timedOutReason(T0 + INACTIVITY + 1), containsString("inactividad"));
        }

        @Test
        @DisplayName("✓ Un heartbeat (markActivity) debe resetear el reloj de inactividad")
        void should_reset_inactivity_on_activity() {
            RealtimeActivityTracker tracker = newTracker();
            tracker.markActivity(T0);
            // Casi al límite, llega un heartbeat que resetea.
            long almost = T0 + INACTIVITY - 1;
            tracker.markActivity(almost);
            // Desde el nuevo marcaje aún no ha pasado el límite.
            assertThat(tracker.isInactivityTimedOut(almost + INACTIVITY), is(false));
            assertThat(tracker.isInactivityTimedOut(almost + INACTIVITY + 1), is(true));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("timedOutReason() — salud combinada")
    class ReasonTests {

        @Test
        @DisplayName("✓ Debe devolver null mientras el proceso está sano")
        void should_return_null_when_healthy() {
            RealtimeActivityTracker tracker = newTracker();
            tracker.markActivity(T0 + 5_000);
            assertThat(tracker.timedOutReason(T0 + 5_000 + INACTIVITY - 1), is(nullValue()));
        }

        @Test
        @DisplayName("✓ Debe priorizar el timeout de arranque sobre el resto")
        void should_report_startup_reason_first() {
            RealtimeActivityTracker tracker = newTracker();
            String reason = tracker.timedOutReason(T0 + STARTUP + 1);
            assertThat(reason, containsString("arranque"));
        }
    }
}
