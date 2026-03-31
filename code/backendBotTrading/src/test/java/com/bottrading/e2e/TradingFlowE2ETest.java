package com.bottrading.e2e;

import com.bottrading.application.market.FetchService;
import com.bottrading.application.market.IndicatorsService;
import com.bottrading.application.strategy.StrategyBacktestApplicationService;
import com.bottrading.domain.market.IndicadorRepository;
import com.bottrading.domain.market.Vela;
import com.bottrading.domain.market.VelaRepository;
import com.bottrading.exceptions.DataFetchException;
import com.bottrading.infrastructure.bridge.PythonBridgeExecutionException;
import com.bottrading.infrastructure.bridge.PythonBridgeFacade;
import com.bottrading.infrastructure.bridge.PythonBridgeRequest;
import com.bottrading.infrastructure.persistence.FileService;
import com.bottrading.services.BacktestingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BLOQUE 3 - End-to-end de flujos críticos (orquestación de servicios).
 *
 * Nota: se mockean puertos externos (PythonBridge, FileService, BacktestingService)
 * para validar el flujo de negocio Java de punta a punta sin procesos externos.
 */
@ExtendWith(MockitoExtension.class)
class TradingFlowE2ETest {

    private static final String STRATEGY_NAME = "RSISMAStrategy";

    @Mock
    private VelaRepository velaRepository;

    @Mock
    private IndicadorRepository indicadorRepository;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private PythonBridgeFacade pythonBridgeFacade;

    @Mock
    private BacktestingService backtestingService;

    @Mock
    private FileService fileService;

    @InjectMocks
    private FetchService fetchService;

    @InjectMocks
    private IndicatorsService indicatorsService;

    @InjectMocks
    private StrategyBacktestApplicationService strategyBacktestApplicationService;

    @Nested
    @DisplayName("Flujo E2E: download -> indicators -> backtest")
    class DownloadIndicatorsBacktestFlow {

        @Test
        @DisplayName("Debe ejecutar flujo completo exitoso para BTCUSDT")
        void should_execute_full_flow_successfully() throws Exception {
            String symbol = "BTCUSDT";
            String interval = "1h";
            long now = System.currentTimeMillis();
            List<Vela> velas = velas(200, symbol, interval);

            // Stage 1: fetch incremental
            when(velaRepository.findMaxOpenTimeBySymbolAndInterval(symbol, interval))
                .thenReturn(now - (10L * 24 * 60 * 60 * 1000));
            when(pythonBridgeFacade.execute(any(PythonBridgeRequest.class)))
                .thenReturn(200)   // FetchService
                .thenReturn(200);  // IndicatorsService

            // Stage 2 + 3: indicators + backtest
            when(velaRepository.findBySymbolAndIntervalOrderByOpenTimeAsc(symbol, interval))
                .thenReturn(velas);
            when(backtestingService.ejecutarBacktest(anyString(), anyString(), anyString(), anyMap(), any(), any(), anyBoolean()))
                .thenReturn("{\"pnl\":1200.00,\"winRate\":62.5}");

            assertDoesNotThrow(() -> ejecutarFlujoCompleto(symbol, interval, List.of(symbol), 7, now));

            verify(pythonBridgeFacade, times(2)).execute(any(PythonBridgeRequest.class));
            verify(indicadorRepository, times(1))
                .deleteByVelaSymbolAndIntervalAndOpenTimeGreaterThanEqual(eq(symbol), eq(interval), anyLong());
            verify(velaRepository, times(1))
                .deleteBySymbolAndIntervalAndOpenTimeGreaterThanEqual(eq(symbol), eq(interval), anyLong());
            verify(backtestingService, times(1))
                .ejecutarBacktest(anyString(), eq(STRATEGY_NAME), eq(interval), anyMap(), any(), any(), eq(true));
            verify(fileService, times(1)).guardarEstadisticasDelBacktest(eq(STRATEGY_NAME), eq(interval), anyString());
        }

        @Test
        @DisplayName("Debe cortar el flujo si falla descarga en FetchService")
        void should_stop_flow_when_fetch_fails() throws Exception {
            String symbol = "BTCUSDT";
            String interval = "1h";
            long now = System.currentTimeMillis();

            when(velaRepository.findMaxOpenTimeBySymbolAndInterval(symbol, interval))
                .thenReturn(now - (10L * 24 * 60 * 60 * 1000));
            when(pythonBridgeFacade.execute(any(PythonBridgeRequest.class)))
                .thenThrow(new PythonBridgeExecutionException("python crashed"));

            assertThrows(DataFetchException.class,
                () -> ejecutarFlujoCompleto(symbol, interval, List.of(symbol), 7, now));

            verify(backtestingService, never())
                .ejecutarBacktest(anyString(), anyString(), anyString(), anyMap(), any(), any(), anyBoolean());
            verify(fileService, never()).guardarEstadisticasDelBacktest(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("Debe procesar flujo para multiples simbolos en backtest")
        void should_execute_flow_for_multiple_symbols() throws Exception {
            long now = System.currentTimeMillis();
            String interval = "1h";

            when(velaRepository.findMaxOpenTimeBySymbolAndInterval("BTCUSDT", interval))
                .thenReturn(now - (10L * 24 * 60 * 60 * 1000));
            when(pythonBridgeFacade.execute(any(PythonBridgeRequest.class)))
                .thenReturn(300)
                .thenReturn(300);

            when(velaRepository.findBySymbolAndIntervalOrderByOpenTimeAsc("BTCUSDT", interval))
                .thenReturn(velas(150, "BTCUSDT", interval));
            when(velaRepository.findBySymbolAndIntervalOrderByOpenTimeAsc("ETHUSDT", interval))
                .thenReturn(velas(150, "ETHUSDT", interval));
            when(backtestingService.ejecutarBacktest(anyString(), anyString(), anyString(), anyMap(), any(), any(), anyBoolean()))
                .thenReturn("{\"pnl\":2200.0}");

            assertDoesNotThrow(() -> ejecutarFlujoCompleto("BTCUSDT", interval, List.of("BTCUSDT", "ETHUSDT"), 7, now));

            verify(velaRepository, atLeast(1))
                .findBySymbolAndIntervalOrderByOpenTimeAsc("BTCUSDT", interval);
            verify(velaRepository, atLeast(1))
                .findBySymbolAndIntervalOrderByOpenTimeAsc("ETHUSDT", interval);
            verify(backtestingService, times(1))
                .ejecutarBacktest(anyString(), eq(STRATEGY_NAME), eq(interval), anyMap(), any(), any(), eq(true));
        }

        @Test
        @DisplayName("Debe soportar volumen alto de velas en etapa de indicadores")
        void should_support_large_dataset_in_indicators_stage() throws Exception {
            String symbol = "BTCUSDT";
            String interval = "1h";
            long now = System.currentTimeMillis();
            List<Vela> manyVelas = velas(250000, symbol, interval);

            when(velaRepository.findMaxOpenTimeBySymbolAndInterval(symbol, interval))
                .thenReturn(now - (10L * 24 * 60 * 60 * 1000));
            when(pythonBridgeFacade.execute(any(PythonBridgeRequest.class)))
                .thenReturn(250000) // fetch
                .thenReturn(100000) // indicators lote 1
                .thenReturn(100000) // indicators lote 2
                .thenReturn(50000); // indicators lote 3

            when(velaRepository.findBySymbolAndIntervalOrderByOpenTimeAsc(symbol, interval))
                .thenReturn(manyVelas);
            when(backtestingService.ejecutarBacktest(anyString(), anyString(), anyString(), anyMap(), any(), any(), anyBoolean()))
                .thenReturn("{\"pnl\":3500.0}");

            assertDoesNotThrow(() -> ejecutarFlujoCompleto(symbol, interval, List.of(symbol), 30, now));

            // fetch + 3 lotes indicators
            verify(pythonBridgeFacade, times(4)).execute(any(PythonBridgeRequest.class));
        }

        @Test
        @DisplayName("Debe no ejecutar motor de backtest con lista vacia de coins")
        void should_not_call_backtesting_engine_when_coin_list_is_empty() throws Exception {
            String symbol = "BTCUSDT";
            String interval = "1h";
            long now = System.currentTimeMillis();

            when(velaRepository.findMaxOpenTimeBySymbolAndInterval(symbol, interval))
                .thenReturn(now - (10L * 24 * 60 * 60 * 1000));
            when(pythonBridgeFacade.execute(any(PythonBridgeRequest.class)))
                .thenReturn(100)
                .thenReturn(100);
            when(velaRepository.findBySymbolAndIntervalOrderByOpenTimeAsc(symbol, interval))
                .thenReturn(velas(100, symbol, interval));

            assertDoesNotThrow(() -> ejecutarFlujoCompleto(symbol, interval, List.of(), 7, now));

            verify(backtestingService, never())
                .ejecutarBacktest(anyString(), anyString(), anyString(), anyMap(), any(), any(), anyBoolean());
        }
    }

    private void ejecutarFlujoCompleto(String symbol,
                                       String interval,
                                       List<String> coinsBacktest,
                                       int dias,
                                       long now) throws Exception {
        fetchService.fetchIncremental(symbol, interval, dias, now);

        List<Vela> velas = velaRepository.findBySymbolAndIntervalOrderByOpenTimeAsc(symbol, interval);
        indicatorsService.calculateBasicIndicators(symbol, velas, true);

        strategyBacktestApplicationService.ejecutarBacktest(
            STRATEGY_NAME,
            interval,
            coinsBacktest,
            new BigDecimal("10000"),
            new BigDecimal("0.02"),
            true,
            true
        );
    }

    private List<Vela> velas(int count, String symbol, String interval) {
        return java.util.stream.IntStream.range(0, count)
            .mapToObj(i -> {
                Vela v = new Vela();
                v.setId((long) (i + 1));
                v.setSymbol(symbol);
                v.setInterval(interval);
                v.setOpenTime(1_700_000_000_000L + (i * 3_600_000L));
                v.setCloseTime(v.getOpenTime() + 3_599_999L);
                v.setOpen(new BigDecimal("40000"));
                v.setHigh(new BigDecimal("40100"));
                v.setLow(new BigDecimal("39900"));
                v.setClose(new BigDecimal("40050"));
                v.setVolume(new BigDecimal("100"));
                v.setQuoteVolume(new BigDecimal("4005000"));
                v.setTrades(100 + i);
                v.setTakerBaseVolume(new BigDecimal("50"));
                v.setTakerQuoteVolume(new BigDecimal("2002500"));
                return v;
            })
            .toList();
    }
}
