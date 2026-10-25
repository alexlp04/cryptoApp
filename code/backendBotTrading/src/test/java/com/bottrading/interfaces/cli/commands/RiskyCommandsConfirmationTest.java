package com.bottrading.interfaces.cli.commands;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.bottrading.interfaces.cli.CliCommandContext;
import com.bottrading.strategy.application.port.in.StrategyCatalogUseCase;
import com.bottrading.strategy.application.port.in.StrategyLifecycleUseCase;
import com.bottrading.user.infrastructure.SessionManager;
import com.bottrading.wallet.application.port.in.WalletManagementUseCase;

class RiskyCommandsConfirmationTest {

    private final List<String> out = new ArrayList<>();
    private final StrategyLifecycleUseCase lifecycle = mock(StrategyLifecycleUseCase.class);

    /** Contexto con mocks, sesión iniciada y las respuestas dadas por teclado. */
    private CliCommandContext context(String input) throws Exception {
        SessionManager session = mock(SessionManager.class);
        when(session.isLoggedIn()).thenReturn(true);
        com.bottrading.user.domain.Usuario usuario = mock(com.bottrading.user.domain.Usuario.class);
        when(usuario.getId()).thenReturn(5L);
        when(session.getCurrentUser()).thenReturn(usuario);
        WalletManagementUseCase wallets = mock(WalletManagementUseCase.class);
        when(wallets.listarWallets()).thenReturn(List.of("principal"));
        when(wallets.obtenerIdPorNombre(anyString())).thenReturn(1L);
        when(wallets.getBalance(anyString())).thenReturn(new BigDecimal("1000"));
        StrategyCatalogUseCase catalog = mock(StrategyCatalogUseCase.class);
        when(catalog.getCapitalComprometido(anyLong())).thenReturn(BigDecimal.ZERO);

        Constructor<?> ctor = CliCommandContext.class.getDeclaredConstructors()[0];
        Object[] args = new Object[ctor.getParameterCount()];
        Class<?>[] types = ctor.getParameterTypes();
        for (int i = 0; i < args.length; i++) {
            args[i] = Scanner.class.equals(types[i]) ? new Scanner(input)
                    : Consumer.class.equals(types[i]) ? (Consumer<String>) out::add
                    : mock(types[i]);
        }
        args[0] = new Scanner(input);
        args[4] = session;
        args[5] = lifecycle;
        args[7] = catalog;
        args[9] = wallets;
        return (CliCommandContext) ctor.newInstance(args);
    }

    @Test
    @DisplayName("term <id> no liquida sin confirmación (#112)")
    void term_by_id_needs_confirmation() throws Exception {
        new TermCommand().execute(new String[] {"term", "7"}, context("n\n"));

        verify(lifecycle, never()).terminarEstrategia(anyLong(), anyLong());
        assertTrue(out.stream().anyMatch(l -> l.contains("cancelada")));
    }

    @Test
    @DisplayName("term <id> liquida si se confirma")
    void term_by_id_runs_when_confirmed() throws Exception {
        new TermCommand().execute(new String[] {"term", "7"}, context("s\n"));

        verify(lifecycle).terminarEstrategia(7L, 5L);
    }

    @Test
    @DisplayName("trade -r exige escribir REAL: otra respuesta cancela el lanzamiento (#112)")
    void real_trade_needs_the_word_real() throws Exception {
        // wallet, capital, riesgo y la confirmación equivocada
        new TradeCommand().execute(
                "trade -r -strategy StressTestStrategy -tf 1h -coins BTCUSDT".split(" "),
                context("principal\n100\n0.02\nsi\n"));

        verify(lifecycle, never()).iniciarTradeRT(anyString(), any(), anyString(), any(), Mockito.anyBoolean(),
                any(), any(), any(), anyString(), any());
        assertTrue(out.stream().anyMatch(l -> l.contains("cancelada")), out.toString());
    }

    @Test
    @DisplayName("trade -r lanza la estrategia cuando se escribe REAL")
    void real_trade_runs_when_real_is_typed() throws Exception {
        new TradeCommand().execute(
                "trade -r -strategy StressTestStrategy -tf 1h -coins BTCUSDT".split(" "),
                context("principal\n100\n0.02\nREAL\n"));

        verify(lifecycle).iniciarTradeRT(anyString(), any(), anyString(), any(), Mockito.eq(true),
                any(), any(), any(), Mockito.eq("close"), any());
    }
}
