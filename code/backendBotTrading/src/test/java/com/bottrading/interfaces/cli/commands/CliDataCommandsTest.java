package com.bottrading.interfaces.cli.commands;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.bottrading.backtesting.application.port.in.ExecuteBacktestUseCase;
import com.bottrading.interfaces.cli.CliCommandContext;
import com.bottrading.market.application.port.in.FetchMarketDataUseCase;
import com.bottrading.strategy.application.port.in.StrategyLifecycleUseCase;
import com.bottrading.training.application.port.in.TrainModelUseCase;
import com.bottrading.user.domain.Usuario;
import com.bottrading.user.infrastructure.SessionManager;

/** Comandos de datos, IA y trading: lo que el usuario escribe llega bien a los servicios, y lo mal escrito no. */
class CliDataCommandsTest {

    private final List<String> out = new ArrayList<>();
    private final SessionManager session = mock(SessionManager.class);
    private final FetchMarketDataUseCase fetch = mock(FetchMarketDataUseCase.class);
    private final TrainModelUseCase train = mock(TrainModelUseCase.class);
    private final ExecuteBacktestUseCase backtest = mock(ExecuteBacktestUseCase.class);
    private final StrategyLifecycleUseCase lifecycle = mock(StrategyLifecycleUseCase.class);

    @BeforeEach
    void login() {
        when(session.isLoggedIn()).thenReturn(true);
        Usuario u = mock(Usuario.class);
        when(u.getId()).thenReturn(5L);
        when(session.getCurrentUser()).thenReturn(u);
    }

    private CliCommandContext context(String input) throws Exception {
        Constructor<?> ctor = CliCommandContext.class.getDeclaredConstructors()[0];
        Class<?>[] types = ctor.getParameterTypes();
        Object[] args = new Object[types.length];
        for (int i = 0; i < args.length; i++) {
            args[i] = Consumer.class.equals(types[i]) ? (Consumer<String>) out::add : mock(types[i]);
        }
        args[0] = new Scanner(input);
        args[4] = session;
        args[5] = lifecycle;
        args[8] = backtest;
        args[10] = train;
        args[13] = fetch;
        return (CliCommandContext) ctor.newInstance(args);
    }

    private boolean said(String fragment) {
        return out.stream().anyMatch(l -> l.contains(fragment));
    }

    private static String[] cmd(String line) {
        return line.split(" ");
    }

    @Test
    @DisplayName("fetch: descarga cada moneda pedida con el timeframe dado")
    void fetch_downloads_each_coin() throws Exception {
        new FetchCommand().execute(cmd("fetch -tf 1h -coins BTCUSDT ETHUSDT -d 30"), context(""));

        verify(fetch).fullRefresh("BTCUSDT", "1h", 30);
        verify(fetch).fullRefresh("ETHUSDT", "1h", 30);
    }

    @Test
    @DisplayName("fetch: la forma posicional <moneda> <timeframe> equivale a la de opciones")
    void fetch_positional_form() throws Exception {
        new FetchCommand().execute(cmd("fetch BTCUSDT 4h"), context(""));

        verify(fetch).fullRefresh(eq("BTCUSDT"), eq("4h"), isNull());
    }

    @Test
    @DisplayName("fetch: sin timeframe o monedas muestra el uso, y -d 0 lo rechaza el parser sin descargar")
    void fetch_rejects_bad_input() throws Exception {
        new FetchCommand().execute(cmd("fetch"), context(""));
        assertTrue(said("Uso: fetch"));

        new FetchCommand().execute(cmd("fetch -tf 1h -coins BTCUSDT -d 0"), context(""));
        assertTrue(said("-days debe ser un entero positivo"));
        verify(fetch, never()).fullRefresh(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("fetch --detect-gaps sin datos previos lo dice y no rellena nada")
    void fetch_gap_mode_without_data() throws Exception {
        when(fetch.findOldestTimestamp("BTCUSDT", "1h")).thenReturn(null);

        new FetchCommand().execute(cmd("fetch BTCUSDT 1h --detect-gaps"), context(""));

        assertTrue(said("No hay datos existentes"));
        verify(fetch, never()).fillGapRange(anyString(), anyString(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("train: lleva modelo, timeframe, moneda, días y estrategia al servicio y muestra su resultado")
    void train_passes_arguments() throws Exception {
        when(train.entrenarModelo(anyString(), anyString(), anyString(), anyInt(), any(), any()))
                .thenReturn("F1: 55%");

        new TrainCommand().execute(
                cmd("train -model random_forest -tf 1h -coins BTCUSDT -strategy StressTestStrategy -d 90"), context(""));

        verify(train).entrenarModelo(eq("random_forest"), eq("1h"), eq("BTCUSDT"), eq(90), any(),
                eq("StressTestStrategy"));
        assertTrue(said("F1: 55%"));
    }

    @Test
    @DisplayName("train: faltan argumentos o hay varias monedas → no entrena")
    void train_rejects_bad_input() throws Exception {
        new TrainCommand().execute(cmd("train -model random_forest"), context(""));
        assertTrue(said("Uso: train"));

        new TrainCommand().execute(cmd("train -model rf -tf 1h -coins BTCUSDT ETHUSDT -d 30"), context(""));

        verify(train, never()).entrenarModelo(anyString(), anyString(), anyString(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("backtest: pide capital, riesgo y opciones, y lanza el caso de uso con ellos")
    void backtest_runs_with_prompts() throws Exception {
        new BacktestCommand().execute(cmd("backtest -strategy EmaCross -tf 4h -coins BTCUSDT"),
                context("10000\n0.02\ns\nn\n"));

        verify(backtest).ejecutarBacktest(eq("EmaCross"), isNull(), eq("4h"), eq(List.of("BTCUSDT")),
                eq(new BigDecimal("10000")), eq(new BigDecimal("0.02")), eq(true), eq(false));
        assertTrue(said("Backtest finalizado"));
    }

    @Test
    @DisplayName("backtest: sin -strategy muestra el uso, y un fallo del servicio se muestra sin tumbar la CLI")
    void backtest_errors() throws Exception {
        new BacktestCommand().execute(cmd("backtest -model rf -tf 4h -coins BTCUSDT"), context(""));
        assertTrue(said("Uso: backtest"));  // sin -strategy ni siquiera llega a pedir el modelo
        verify(backtest, never()).ejecutarBacktest(any(), any(), any(), any(), any(), any(), anyBoolean(),
                anyBoolean());

        doThrow(new IllegalStateException("sin datos")).when(backtest)
                .ejecutarBacktest(any(), any(), any(), any(), any(), any(), anyBoolean(), anyBoolean());
        new BacktestCommand().execute(cmd("backtest -strategy EmaCross -tf 4h -coins BTCUSDT"),
                context("10000\n0.02\nn\nn\n"));
        assertTrue(said("Error: sin datos"));
    }

    @Test
    @DisplayName("start: por id, con -all y con un fallo del servicio")
    void start() throws Exception {
        new StartCommand().execute(cmd("start 7"), context(""));
        verify(lifecycle).iniciarEstrategiaDetenida(7L, 5L);

        new StartCommand().execute(cmd("start -all"), context(""));
        verify(lifecycle).iniciarTodasDetenidas(5L);

        doThrow(new IllegalStateException("ya activa")).when(lifecycle).iniciarEstrategiaDetenida(8L, 5L);
        new StartCommand().execute(cmd("start 8"), context(""));
        assertTrue(said("Error: ya activa"));
    }

    @Test
    @DisplayName("trade -r se rechaza: no hay exchange conectado")
    void trade_real_is_rejected() throws Exception {
        new TradeCommand().execute(cmd("trade -r -strategy EmaCross -tf 1h -coins BTCUSDT"), context(""));

        assertTrue(said("no está disponible"));
        verify(lifecycle, never()).iniciarTradeRT(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(),
                any());
    }
}
