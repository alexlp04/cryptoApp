package com.bottrading.trading.infrastructure.bridge;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("RestartBackoffPolicy — reinicio con backoff y anti crash-loop")
class RestartBackoffPolicyTest {

    private static final long BASE = 5_000L;
    private static final long MAX = 300_000L;
    private static final int MAX_RESTARTS = 5;
    private static final long STABLE = 120_000L;

    private RestartBackoffPolicy newPolicy() {
        return new RestartBackoffPolicy(BASE, MAX, MAX_RESTARTS, STABLE);
    }

    /** Uptime que NO se considera estable (crash inmediato). */
    private static final long UNSTABLE = 1_000L;

    @Nested
    @DisplayName("CONSTRUCCIÓN")
    class ConstructionTests {

        @Test
        @DisplayName("✓ Debe rechazar base no positiva")
        void should_reject_non_positive_base() {
            assertThrows(IllegalArgumentException.class,
                    () -> new RestartBackoffPolicy(0L, MAX, MAX_RESTARTS, STABLE));
        }

        @Test
        @DisplayName("✓ Debe rechazar max < base")
        void should_reject_max_below_base() {
            assertThrows(IllegalArgumentException.class,
                    () -> new RestartBackoffPolicy(BASE, BASE - 1, MAX_RESTARTS, STABLE));
        }

        @Test
        @DisplayName("✓ Debe rechazar maxConsecutiveRestarts no positivo")
        void should_reject_non_positive_max_restarts() {
            assertThrows(IllegalArgumentException.class,
                    () -> new RestartBackoffPolicy(BASE, MAX, 0, STABLE));
        }

        @Test
        @DisplayName("✓ Recién creado no debe tener reinicios")
        void should_start_at_zero() {
            assertThat(newPolicy().consecutiveRestarts(), is(0));
        }
    }

    @Nested
    @DisplayName("BACKOFF EXPONENCIAL")
    class BackoffTests {

        @Test
        @DisplayName("✓ El primer reinicio tras un crash usa el retraso base")
        void should_use_base_on_first_restart() {
            RestartBackoffPolicy p = newPolicy();
            assertThat(p.onRunEnded(UNSTABLE), is(BASE));
            assertThat(p.consecutiveRestarts(), is(1));
        }

        @Test
        @DisplayName("✓ El retraso se duplica en crashes sucesivos")
        void should_double_each_time() {
            RestartBackoffPolicy p = newPolicy();
            assertThat(p.onRunEnded(UNSTABLE), is(BASE));       // 5s
            assertThat(p.onRunEnded(UNSTABLE), is(BASE * 2));   // 10s
            assertThat(p.onRunEnded(UNSTABLE), is(BASE * 4));   // 20s
            assertThat(p.onRunEnded(UNSTABLE), is(BASE * 8));   // 40s
        }

        @Test
        @DisplayName("✓ El retraso nunca supera el tope máximo")
        void should_cap_at_max() {
            RestartBackoffPolicy p = new RestartBackoffPolicy(BASE, 30_000L, 100, STABLE);
            long last = 0;
            for (int i = 0; i < 20; i++) {
                last = p.onRunEnded(UNSTABLE);
                assertThat(last, is(org.hamcrest.Matchers.lessThanOrEqualTo(30_000L)));
            }
            assertThat(last, is(30_000L));
        }
    }

    @Nested
    @DisplayName("RESET POR EJECUCIÓN ESTABLE")
    class StableResetTests {

        @Test
        @DisplayName("✓ Un uptime estable resetea el contador antes de contar el reinicio")
        void should_reset_after_stable_run() {
            RestartBackoffPolicy p = newPolicy();
            p.onRunEnded(UNSTABLE);   // #1
            p.onRunEnded(UNSTABLE);   // #2
            assertThat(p.consecutiveRestarts(), is(2));

            // Corrió estable y luego cayó: se trata como primer reinicio otra vez.
            long delay = p.onRunEnded(STABLE + 1);
            assertThat(delay, is(BASE));
            assertThat(p.consecutiveRestarts(), is(1));
        }
    }

    @Nested
    @DisplayName("ANTI CRASH-LOOP")
    class CrashLoopTests {

        @Test
        @DisplayName("✓ Debe abortar (-1) tras superar el máximo de reinicios consecutivos")
        void should_give_up_after_max() {
            RestartBackoffPolicy p = newPolicy();
            for (int i = 0; i < MAX_RESTARTS; i++) {
                assertThat("reinicio " + i + " debe seguir permitido",
                        p.onRunEnded(UNSTABLE), is(org.hamcrest.Matchers.greaterThan(0L)));
            }
            // El (MAX_RESTARTS + 1)-ésimo debe abortar.
            assertThat(p.onRunEnded(UNSTABLE), is(lessThan(0L)));
        }

        @Test
        @DisplayName("✓ Una racha estable evita el aborto (el contador se resetea)")
        void should_survive_if_runs_stabilize() {
            RestartBackoffPolicy p = newPolicy();
            for (int i = 0; i < 20; i++) {
                long delay = p.onRunEnded(STABLE + 1);   // siempre estable
                assertThat(delay, is(BASE));             // nunca escala ni aborta
            }
        }
    }
}
