package com.bottrading.trading.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.bottrading.strategy.domain.EstadoEstrategia;
import com.bottrading.strategy.domain.InstanciaEstrategia;
import com.bottrading.strategy.domain.InstanciaEstrategiaRepository;
import com.bottrading.trading.application.port.in.AccountingUseCase;
import com.bottrading.trading.application.port.out.PosicionRepositoryPort;
import com.bottrading.trading.domain.Posicion;
import com.bottrading.trading.infrastructure.bridge.ProcessedSignalRegistry;
import com.bottrading.trading.infrastructure.bridge.SignalDTO;

/**
 * Paridad con el backtest: las mismas operaciones dan el mismo PnL neto en Java (paper trading) que en
 * {@code backtest_engine.run_backtest} (Python), con 10 000 de capital, riesgo del 1 %, comisión del 0,1 %
 * por lado y 0,05 % de slippage. Los PnL esperados salen de ejecutar el backtest sobre velas de dos pasos
 * (señal en la primera, salida en la segunda); si cambian los costes o el dimensionado hay que regenerarlos
 * en los dos lados a la vez.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BacktestParityTest {

    @Mock
    private AccountingUseCase accountingService;
    @Mock
    private InstanciaEstrategiaRepository instanciaRepo;
    @Mock
    private PosicionRepositoryPort posicionRepo;
    @Mock
    private ProcessedSignalRegistry signalRegistry;

    @InjectMocks
    private PaperTradingService service;

    @ParameterizedTest(name = "{0}")
    @DisplayName("El PnL neto de Java coincide con el del backtest de Python")
    @CsvSource({
        // nombre, apertura, stop, objetivo, cierre, precio de cierre, motivo, PnL del backtest
        "largo cerrado por señal,       BUY,   95,  120, SELL,  110, SIGNAL,  191.78227722772317",
        "corto cerrado por señal,       SHORT, 105,  90, COVER,  95, SIGNAL,   93.21787128712822",
        "largo que salta el stop,       BUY,   95,  120, SELL,   95, STOP,   -104.80202970297069",
        "largo que llega al objetivo,   BUY,   95,  120, SELL,  120, TARGET,  390.6920792079218",
        "corto que salta el stop,       SHORT, 105,  90, COVER, 105, STOP,   -105.09905940594035",
        "corto que llega al objetivo,   SHORT, 105,  90, COVER,  90, TARGET,  193.26831683168348",
    })
    void should_match_the_python_backtest(String nombre, String apertura, String stop, String objetivo,
            String cierre, String precioCierre, String motivo, String pnlEsperado) {
        InstanciaEstrategia inst = InstanciaEstrategia.inicializar("Estrategia", "modelo", "1h",
                List.of("BTCUSDT"), false, 1L, new BigDecimal("0.01"), new BigDecimal("10000"));
        inst.setId(10L);
        inst.setEstado(EstadoEstrategia.ACTIVA);
        inst.setWalletAsociada(1L);
        inst.setCapitalReservado(new BigDecimal("10000"));
        inst.setCapitalComprometido(BigDecimal.ZERO);
        inst.setRiesgoAbierto(BigDecimal.ZERO);
        when(instanciaRepo.findByIdWithLock(10L)).thenReturn(Optional.of(inst));
        when(posicionRepo.existsByInstanciaAndSimboloAndAbiertaTrue(any(), anyString())).thenReturn(false);
        when(posicionRepo.save(any(Posicion.class))).thenAnswer(inv -> inv.getArgument(0));

        SignalDTO abrir = senal(apertura, "100", "SIGNAL", 1L);
        abrir.setStopLoss(new BigDecimal(stop));
        abrir.setTakeProfit(new BigDecimal(objetivo));
        service.onSignal(10L, abrir);
        ArgumentCaptor<Posicion> guardada = ArgumentCaptor.forClass(Posicion.class);
        verify(posicionRepo).save(guardada.capture());
        when(posicionRepo.findByInstanciaAndSimboloAndAbiertaTrue(any(), eq("BTCUSDT")))
                .thenReturn(Optional.of(guardada.getValue()));

        service.onSignal(10L, senal(cierre, precioCierre, motivo, 2L));

        ArgumentCaptor<BigDecimal> pnl = ArgumentCaptor.forClass(BigDecimal.class);
        verify(accountingService).closeTrade(any(), eq(10L), any(), pnl.capture(), any(), any());
        assertEquals(Double.parseDouble(pnlEsperado), pnl.getValue().doubleValue(), 1e-4, nombre);
    }

    private SignalDTO senal(String accion, String precio, String motivo, long timestamp) {
        SignalDTO s = new SignalDTO();
        s.setAction(accion);
        s.setSymbol("BTCUSDT");
        s.setPrice(new BigDecimal(precio));
        s.setTimeframe("1h");
        s.setTimestamp(timestamp);
        s.setReason(motivo);
        return s;
    }
}
