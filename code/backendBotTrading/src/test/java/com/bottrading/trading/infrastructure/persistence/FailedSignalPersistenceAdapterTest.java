package com.bottrading.trading.infrastructure.persistence;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.bottrading.trading.domain.SenalFallidaPendiente;
import com.bottrading.trading.domain.SenalFallidaPendienteRepository;
import com.bottrading.trading.infrastructure.bridge.SignalDTO;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("FailedSignalPersistenceAdapter — persistencia de señales fallidas")
class FailedSignalPersistenceAdapterTest {

    @Mock
    private SenalFallidaPendienteRepository repository;

    private FailedSignalPersistenceAdapter adapter;

    @Captor
    private ArgumentCaptor<SenalFallidaPendiente> entityCaptor;

    @BeforeEach
    void setup() {
        adapter = new FailedSignalPersistenceAdapter(repository);
    }

    private SignalDTO signal(String symbol, String action, long ts) {
        SignalDTO s = new SignalDTO();
        s.setSymbol(symbol);
        s.setAction(action);
        s.setPrice(new BigDecimal("40000"));
        s.setTimeframe("1h");
        s.setTimestamp(ts);
        return s;
    }

    private SenalFallidaPendiente entity(Long instanciaId, String symbol, String action, long ts) {
        SenalFallidaPendiente e = new SenalFallidaPendiente();
        e.setInstanciaId(instanciaId);
        e.setClaveSenal(symbol + "|1h|" + action + "|" + ts);
        e.setSymbol(symbol);
        e.setAction(action);
        e.setPrice(new BigDecimal("40000"));
        e.setTimeframe("1h");
        e.setSignalTimestamp(ts);
        return e;
    }

    @Nested
    @DisplayName("save()")
    class SaveTests {

        @Test
        @DisplayName("✓ Debe mapear todos los campos de la señal a la entidad")
        void should_map_all_fields() {
            adapter.save(7L, signal("BTCUSDT", "BUY", 1000L));

            verify(repository).save(entityCaptor.capture());
            SenalFallidaPendiente e = entityCaptor.getValue();
            assertThat(e.getInstanciaId(), is(7L));
            assertThat(e.getSymbol(), is("BTCUSDT"));
            assertThat(e.getAction(), is("BUY"));
            assertThat(e.getTimeframe(), is("1h"));
            assertThat(e.getSignalTimestamp(), is(1000L));
            assertThat(e.getClaveSenal(), is("BTCUSDT|1h|BUY|1000"));
        }
    }

    @Nested
    @DisplayName("markProcessed()")
    class MarkProcessedTests {

        @Test
        @DisplayName("✓ Debe soft-delete la entidad coincidente")
        void should_soft_delete_matching_entity() {
            SenalFallidaPendiente e = entity(7L, "BTCUSDT", "BUY", 1000L);
            when(repository.findFirstByInstanciaIdAndClaveSenalAndEliminadoFalse(7L, "BTCUSDT|1h|BUY|1000"))
                    .thenReturn(Optional.of(e));

            adapter.markProcessed(7L, signal("BTCUSDT", "BUY", 1000L));

            assertThat(e.isEliminado(), is(true));
            verify(repository).save(e);
        }

        @Test
        @DisplayName("✓ No debe fallar si no hay entidad coincidente")
        void should_be_noop_when_no_match() {
            when(repository.findFirstByInstanciaIdAndClaveSenalAndEliminadoFalse(any(), any()))
                    .thenReturn(Optional.empty());

            adapter.markProcessed(7L, signal("BTCUSDT", "BUY", 1000L));

            verify(repository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("deleteAllForInstance()")
    class DeleteAllTests {

        @Test
        @DisplayName("✓ Debe soft-delete todas las pendientes de la instancia")
        void should_soft_delete_all_pending() {
            SenalFallidaPendiente e1 = entity(7L, "BTCUSDT", "BUY", 1000L);
            SenalFallidaPendiente e2 = entity(7L, "ETHUSDT", "SELL", 2000L);
            when(repository.findByInstanciaIdAndEliminadoFalse(7L)).thenReturn(List.of(e1, e2));

            adapter.deleteAllForInstance(7L);

            assertThat(e1.isEliminado(), is(true));
            assertThat(e2.isEliminado(), is(true));
            verify(repository, times(1)).saveAll(List.of(e1, e2));
        }
    }

    @Nested
    @DisplayName("loadPending() / instancesWithPending()")
    class LoadTests {

        @Test
        @DisplayName("✓ Debe mapear entidades pendientes de vuelta a SignalDTO")
        void should_map_entities_back_to_dto() {
            when(repository.findByInstanciaIdAndEliminadoFalse(7L))
                    .thenReturn(List.of(entity(7L, "BTCUSDT", "BUY", 1000L)));

            List<SignalDTO> pend = adapter.loadPending(7L);

            assertThat(pend, hasSize(1));
            assertThat(pend.get(0).getSymbol(), is("BTCUSDT"));
            assertThat(pend.get(0).getAction(), is("BUY"));
            assertThat(pend.get(0).getTimestamp(), is(1000L));
        }

        @Test
        @DisplayName("✓ Debe devolver instancias distintas con pendientes")
        void should_return_distinct_instances() {
            when(repository.findByEliminadoFalse()).thenReturn(List.of(
                    entity(7L, "BTCUSDT", "BUY", 1000L),
                    entity(7L, "ETHUSDT", "SELL", 2000L),
                    entity(9L, "BNBUSDT", "BUY", 3000L)));

            assertThat(adapter.instancesWithPending(), containsInAnyOrder(7L, 9L));
        }
    }

    @Test
    @DisplayName("✓ claveDe genera clave determinista symbol|timeframe|action|timestamp")
    void should_build_deterministic_key() {
        assertThat(List.of(FailedSignalPersistenceAdapter.claveDe(signal("BTCUSDT", "BUY", 1000L))),
                contains("BTCUSDT|1h|BUY|1000"));
    }

    @Test
    @DisplayName("✓ save no debe invocarse en loadPending (solo lectura)")
    void should_not_write_on_load() {
        when(repository.findByInstanciaIdAndEliminadoFalse(eq(7L))).thenReturn(List.of());
        adapter.loadPending(7L);
        verify(repository, never()).save(any());
    }
}
