package com.bottrading.application.market;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import com.bottrading.domain.market.IndicadorRepository;
import com.bottrading.domain.market.VelaRepository;
import com.bottrading.exceptions.DataFetchException;
import com.bottrading.infrastructure.bridge.PythonBridgeExecutionException;
import com.bottrading.infrastructure.bridge.PythonBridgeFacade;

@ExtendWith(MockitoExtension.class)
class FetchServiceTest {

    private static final String SYMBOL = "BTCUSDT";
    private static final String INTERVAL = "1h";

    @Mock
    private VelaRepository velaRepository;

    @Mock
    private IndicadorRepository indicadorRepository;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private PythonBridgeFacade pythonBridgeFacade;

    @InjectMocks
    private FetchService fetchService;

    @Test
    void fetchCallsPythonWhenNoPriorData() throws Exception {
        when(velaRepository.findMaxOpenTimeBySymbolAndInterval(SYMBOL, INTERVAL))
                .thenReturn(null);
        when(pythonBridgeFacade.execute(any())).thenReturn(10);

        fetchService.fetch(SYMBOL, INTERVAL);

        verify(pythonBridgeFacade, times(1)).execute(any());
    }

    @Test
        void fetchSkipsPythonWhenDataIsUpToDate() throws Exception {
        long now = System.currentTimeMillis();
        long lastTimestamp = now - (30L * 60L * 1000L);
        long minTimestamp = now - (24L * 60L * 60L * 1000L);
        long intervalMillis = 60L * 60L * 1000L;
        long expectedCount = ((lastTimestamp - minTimestamp) / intervalMillis) + 1;

        when(velaRepository.findMaxOpenTimeBySymbolAndInterval(SYMBOL, INTERVAL))
                .thenReturn(lastTimestamp);
        when(velaRepository.findMinOpenTimeBySymbolAndInterval(SYMBOL, INTERVAL))
                .thenReturn(minTimestamp);
        when(velaRepository.countBySymbolAndIntervalAndOpenTimeBetween(
                SYMBOL, INTERVAL, minTimestamp, lastTimestamp))
                .thenReturn(expectedCount);

        fetchService.fetch(SYMBOL, INTERVAL);

        verify(pythonBridgeFacade, never()).execute(any());
    }

    @Test
    void fetchIncrementalReturnsTargetTimestampWhenNoPriorData() throws Exception {
        int dias = 7;
        long now = System.currentTimeMillis();
        long targetTimestamp = now - (dias * 24L * 60L * 60L * 1000L);

        when(velaRepository.findMaxOpenTimeBySymbolAndInterval(SYMBOL, INTERVAL))
                .thenReturn(null);
        doNothing().when(indicadorRepository)
                .deleteByVelaSymbolAndIntervalAndOpenTimeGreaterThanEqual(
                        SYMBOL, INTERVAL, targetTimestamp);
        doNothing().when(velaRepository)
                .deleteBySymbolAndIntervalAndOpenTimeGreaterThanEqual(
                        SYMBOL, INTERVAL, targetTimestamp);
        when(pythonBridgeFacade.execute(any())).thenReturn(100);

        long result = fetchService.fetchIncremental(SYMBOL, INTERVAL, dias, now);

        assertThat(result, equalTo(targetTimestamp));
        verify(pythonBridgeFacade, times(1)).execute(any());
    }

    @Test
    void fetchWrapsBridgeExceptionInDataFetchException() throws Exception {
        when(velaRepository.findMaxOpenTimeBySymbolAndInterval(SYMBOL, INTERVAL))
                .thenReturn(null);
        when(pythonBridgeFacade.execute(any()))
                .thenThrow(new PythonBridgeExecutionException("bridge failed"));

        org.junit.jupiter.api.Assertions.assertThrows(
                DataFetchException.class,
                () -> fetchService.fetch(SYMBOL, INTERVAL));
    }
}
