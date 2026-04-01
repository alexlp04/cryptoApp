package com.bottrading.application.market;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import com.bottrading.domain.market.Vela;
import com.bottrading.infrastructure.bridge.PythonBridgeExecutionException;
import com.bottrading.infrastructure.bridge.PythonBridgeFacade;

@ExtendWith(MockitoExtension.class)
class IndicatorsServiceTest {

    private static final String SYMBOL = "BTCUSDT";

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private PythonBridgeFacade pythonBridgeFacade;

    @InjectMocks
    private IndicatorsService indicatorsService;

    @Test
    void calculateBasicIndicatorsSkipsWhenVelasIsEmpty() throws Exception {
        indicatorsService.calculateBasicIndicators(SYMBOL, List.of(), false);

        verify(pythonBridgeFacade, never()).execute(any());
    }

    @Test
    void calculateBasicIndicatorsCallsPythonBridgeForValidInput() throws Exception {
        List<Vela> velas = createVelas(100);
        when(pythonBridgeFacade.execute(any())).thenReturn(100);

        indicatorsService.calculateBasicIndicators(SYMBOL, velas, false);

        verify(pythonBridgeFacade, times(1)).execute(any());
    }

    @Test
    void calculateBasicIndicatorsDoesNotPropagateBridgeFailure() throws Exception {
        List<Vela> velas = createVelas(50);
        when(pythonBridgeFacade.execute(any()))
                .thenThrow(new PythonBridgeExecutionException("error"));

        assertDoesNotThrow(() ->
                indicatorsService.calculateBasicIndicators(SYMBOL, velas, false));
    }

    private List<Vela> createVelas(int count) {
        List<Vela> velas = new ArrayList<>();
        long openTime = System.currentTimeMillis();

        for (int i = 0; i < count; i++) {
            Vela vela = new Vela();
            vela.setSymbol(SYMBOL);
            vela.setInterval("1h");
            vela.setOpenTime(openTime);
            vela.setCloseTime(openTime + 3_600_000L);
            vela.setOpen(new BigDecimal("40000.00"));
            vela.setHigh(new BigDecimal("40500.00"));
            vela.setLow(new BigDecimal("39500.00"));
            vela.setClose(new BigDecimal("40200.00"));
            vela.setVolume(new BigDecimal("1000.50"));
            vela.setQuoteVolume(new BigDecimal("40200000.00"));
            vela.setTrades(5000);
            vela.setTakerBaseVolume(new BigDecimal("800.00"));
            vela.setTakerQuoteVolume(new BigDecimal("32160000.00"));
            velas.add(vela);
            openTime += 3_600_000L;
        }

        return velas;
    }
}
