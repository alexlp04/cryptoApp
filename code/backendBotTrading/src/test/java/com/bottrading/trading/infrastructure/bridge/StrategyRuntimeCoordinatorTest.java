package com.bottrading.trading.infrastructure.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.bottrading.strategy.domain.InstanciaEstrategia;
import com.bottrading.trading.application.port.in.AccountingUseCase;
import com.bottrading.trading.application.port.in.ProcessSignalUseCase;

class StrategyRuntimeCoordinatorTest {

    @Test
    @DisplayName("Una instancia con runtime en marcha no lanza un segundo proceso Python (#82)")
    void second_launch_for_same_instance_is_ignored() throws Exception {
        RealtimeProcessSupervisor supervisor = mock(RealtimeProcessSupervisor.class);
        CountDownLatch release = new CountDownLatch(1);
        when(supervisor.iniciarProcesoPython(any())).thenAnswer(inv -> {
            release.await(); // el primer runtime queda "vivo" hasta que el test lo suelte
            throw new IllegalStateException("fin");
        });
        StrategyRuntimeCoordinator coordinator = new StrategyRuntimeCoordinator(supervisor,
                mock(SignalProtocolParser.class), mock(SignalRetryQueueService.class),
                mock(ProcessSignalUseCase.class), mock(AccountingUseCase.class), mock(PythonBridgeFacade.class));
        InstanciaEstrategia inst = InstanciaEstrategia.inicializar("S", null, "1h", List.of("BTCUSDT"), false, 1L,
                new BigDecimal("0.01"), new BigDecimal("100"));
        inst.setId(7L);

        try {
            coordinator.ejecutarTradeEnTiempoReal(inst, List.of("BTCUSDT"));
            coordinator.ejecutarTradeEnTiempoReal(inst, List.of("BTCUSDT"));

            verify(supervisor, timeout(2000).times(1)).iniciarProcesoPython(any());
            assertEquals(1, coordinator.getIdsEstrategiasActivas().size());
            assertTrue(coordinator.getIdsEstrategiasActivas().contains(7L));
        } finally {
            release.countDown();
            coordinator.apagarSistema();
        }
        verify(supervisor, times(1)).iniciarProcesoPython(any());
    }
}
