package com.bottrading.trading.infrastructure.bridge;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ProcessedSignalRegistry — dedup de señales por idempotencia")
class ProcessedSignalRegistryTest {

    @Nested
    @DisplayName("CONSTRUCCIÓN")
    class ConstructionTests {

        @Test
        @DisplayName("✓ Debe rechazar capacidad no positiva")
        void should_reject_non_positive_capacity() {
            assertThrows(IllegalArgumentException.class, () -> new ProcessedSignalRegistry(0));
        }

        @Test
        @DisplayName("✓ Recién creado no debe conocer ninguna clave")
        void should_start_empty() {
            ProcessedSignalRegistry reg = new ProcessedSignalRegistry();
            assertThat(reg.seen(1L, "k"), is(false));
            assertThat(reg.size(1L), is(0));
        }
    }

    @Nested
    @DisplayName("seen() / mark()")
    class SeenMarkTests {

        @Test
        @DisplayName("✓ Una clave marcada debe reconocerse como vista")
        void should_recognize_marked_key() {
            ProcessedSignalRegistry reg = new ProcessedSignalRegistry();
            assertThat(reg.seen(1L, "BTCUSDT|1h|BUY|1000"), is(false));
            reg.mark(1L, "BTCUSDT|1h|BUY|1000");
            assertThat(reg.seen(1L, "BTCUSDT|1h|BUY|1000"), is(true));
        }

        @Test
        @DisplayName("✓ Marcar dos veces la misma clave es idempotente")
        void should_be_idempotent_on_double_mark() {
            ProcessedSignalRegistry reg = new ProcessedSignalRegistry();
            reg.mark(1L, "k");
            reg.mark(1L, "k");
            assertThat(reg.size(1L), is(1));
        }

        @Test
        @DisplayName("✓ Las claves están aisladas por instancia")
        void should_isolate_by_instance() {
            ProcessedSignalRegistry reg = new ProcessedSignalRegistry();
            reg.mark(1L, "k");
            assertThat(reg.seen(1L, "k"), is(true));
            assertThat(reg.seen(2L, "k"), is(false));
        }
    }

    @Nested
    @DisplayName("COTA DE MEMORIA (FIFO)")
    class BoundedTests {

        @Test
        @DisplayName("✓ No debe superar la capacidad máxima por instancia")
        void should_not_exceed_capacity() {
            ProcessedSignalRegistry reg = new ProcessedSignalRegistry(3);
            for (int i = 0; i < 10; i++) {
                reg.mark(1L, "k" + i);
                assertThat(reg.size(1L), is(lessThanOrEqualTo(3)));
            }
            assertThat(reg.size(1L), is(3));
        }

        @Test
        @DisplayName("✓ Al superar la capacidad, desaloja las claves más antiguas (FIFO)")
        void should_evict_oldest_first() {
            ProcessedSignalRegistry reg = new ProcessedSignalRegistry(2);
            reg.mark(1L, "k1");
            reg.mark(1L, "k2");
            reg.mark(1L, "k3");   // desaloja k1
            assertThat(reg.seen(1L, "k1"), is(false));
            assertThat(reg.seen(1L, "k2"), is(true));
            assertThat(reg.seen(1L, "k3"), is(true));
        }
    }

    @Nested
    @DisplayName("cleanup()")
    class CleanupTests {

        @Test
        @DisplayName("✓ Debe olvidar todas las claves de una instancia")
        void should_forget_instance_keys() {
            ProcessedSignalRegistry reg = new ProcessedSignalRegistry();
            reg.mark(1L, "k");
            reg.cleanup(1L);
            assertThat(reg.seen(1L, "k"), is(false));
            assertThat(reg.size(1L), is(0));
        }
    }
}
