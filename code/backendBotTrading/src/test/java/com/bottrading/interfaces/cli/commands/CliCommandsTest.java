package com.bottrading.interfaces.cli.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Scanner;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.bottrading.interfaces.cli.CliCommandContext;
import com.bottrading.shared.exceptions.ValidationException;
import com.bottrading.strategy.application.port.in.StrategyLifecycleUseCase;
import com.bottrading.user.application.port.in.AuthenticateUseCase;
import com.bottrading.user.application.port.in.CreateUsuarioUseCase;
import com.bottrading.user.domain.Usuario;
import com.bottrading.user.infrastructure.SessionManager;
import com.bottrading.wallet.application.port.in.WalletManagementUseCase;

/** Comandos de la CLI instanciados con dobles, sin levantar Spring (AppBot arrancaría la CLI interactiva). */
class CliCommandsTest {

    private final List<String> out = new ArrayList<>();
    private final SessionManager session = mock(SessionManager.class);
    private final AuthenticateUseCase auth = mock(AuthenticateUseCase.class);
    private final CreateUsuarioUseCase create = mock(CreateUsuarioUseCase.class);
    private final StrategyLifecycleUseCase lifecycle = mock(StrategyLifecycleUseCase.class);
    private final WalletManagementUseCase wallets = mock(WalletManagementUseCase.class);

    private CliCommandContext context(String input) throws Exception {
        Constructor<?> ctor = CliCommandContext.class.getDeclaredConstructors()[0];
        Class<?>[] types = ctor.getParameterTypes();
        Object[] args = new Object[types.length];
        for (int i = 0; i < args.length; i++) {
            args[i] = Consumer.class.equals(types[i]) ? (Consumer<String>) out::add : mock(types[i]);
        }
        args[0] = new Scanner(input);
        args[1] = create;
        args[3] = auth;
        args[4] = session;
        args[5] = lifecycle;
        args[9] = wallets;
        return (CliCommandContext) ctor.newInstance(args);
    }

    private void logged(boolean value) {
        when(session.isLoggedIn()).thenReturn(value);
        Usuario u = mock(Usuario.class);
        when(u.getId()).thenReturn(5L);
        when(u.getNombre()).thenReturn("alex");
        when(session.getCurrentUser()).thenReturn(u);
    }

    private boolean said(String fragment) {
        return out.stream().anyMatch(l -> l.contains(fragment));
    }

    @Test
    @DisplayName("login: con credenciales buenas inicia sesión con el usuario autenticado")
    void login_ok() throws Exception {
        logged(false);
        Usuario u = mock(Usuario.class);
        when(u.getNombre()).thenReturn("alex");
        when(auth.autenticar("alex", "clave")).thenReturn(Optional.of(u));

        new LoginCommand().execute(new String[] {"login"}, context("alex\nclave\n"));

        verify(session).login(u);
        assertTrue(said("Sesion iniciada como alex"));
    }

    @Test
    @DisplayName("login: credenciales malas no inician sesión; con sesión previa ni se pregunta")
    void login_fail_and_already_logged() throws Exception {
        logged(false);
        when(auth.autenticar(anyString(), anyString())).thenReturn(Optional.empty());

        new LoginCommand().execute(new String[] {"login"}, context("alex\nmala\n"));

        verify(session, never()).login(any());
        assertTrue(said("Credenciales incorrectas"));

        out.clear();
        logged(true);
        new LoginCommand().execute(new String[] {"login"}, context(""));
        assertTrue(said("Ya hay una sesion activa"));
    }

    @Test
    @DisplayName("logout: cierra la sesión o avisa de que no hay ninguna")
    void logout() throws Exception {
        logged(true);
        new LogoutCommand().execute(new String[] {"logout"}, context(""));
        verify(session).logout();

        out.clear();
        logged(false);
        new LogoutCommand().execute(new String[] {"logout"}, context(""));
        assertTrue(said("No hay ninguna sesion"));
    }

    @Test
    @DisplayName("signup: registra, y un error de validación se muestra sin tumbar la CLI")
    void signup() throws Exception {
        logged(false);
        new SignupCommand().execute(new String[] {"signup"}, context("alex\nclave\n"));
        verify(create).registrar("alex", "clave");
        assertTrue(said("Registro exitoso"));

        out.clear();
        doThrow(new ValidationException("nombre en uso")).when(create).registrar(anyString(), anyString());
        new SignupCommand().execute(new String[] {"signup"}, context("alex\nclave\n"));
        assertTrue(said("No se pudo registrar: nombre en uso"));
    }

    @Test
    @DisplayName("mkpwallet: saldo por defecto 10000, y rechaza saldos no numéricos o no positivos")
    void mkpwallet() throws Exception {
        logged(true);
        new CreatePaperWalletCommand().execute(new String[] {"mkpwallet", "w"}, context(""));
        verify(wallets).crearWallet("w", new BigDecimal("10000.00"), false);

        new CreatePaperWalletCommand().execute(new String[] {"mkpwallet", "w", "abc"}, context(""));
        new CreatePaperWalletCommand().execute(new String[] {"mkpwallet", "w", "-5"}, context(""));
        new CreatePaperWalletCommand().execute(new String[] {"mkpwallet"}, context(""));

        verify(wallets).crearWallet(anyString(), any(BigDecimal.class), anyBoolean()); // solo la primera
        assertTrue(said("Saldo inválido"));
        assertTrue(said("positivo"));
        assertTrue(said("Uso: mkpwallet"));
    }

    @Test
    @DisplayName("lw: lista las wallets, y exige sesión")
    void list_wallets() throws Exception {
        when(wallets.listarWallets()).thenReturn(List.of("principal"));
        logged(false);
        new ListWalletsCommand().execute(new String[] {"lw"}, context(""));
        assertTrue(said("Debes hacer 'login'"));

        logged(true);
        new ListWalletsCommand().execute(new String[] {"lw"}, context(""));
        assertTrue(said("principal"));
    }

    @Test
    @DisplayName("stop: por id, con -all, con un id que no es número y sin argumentos")
    void stop() throws Exception {
        logged(true);
        new StopCommand().execute(new String[] {"stop", "7"}, context(""));
        verify(lifecycle).detenerEstrategia(7L, 5L);

        new StopCommand().execute(new String[] {"stop", "-all"}, context(""));
        verify(lifecycle).detenerTodas(5L);

        new StopCommand().execute(new String[] {"stop", "siete"}, context(""));
        new StopCommand().execute(new String[] {"stop"}, context(""));
        verify(lifecycle).detenerEstrategia(anyLong(), anyLong()); // la de id 7 y ninguna más
        assertTrue(said("Uso: stop"));
    }

    @Test
    @DisplayName("ayuda: menciona todos los comandos que registra la aplicación")
    void help_lists_every_command() throws Exception {
        new HelpCommand().execute(new String[] {"ayuda"}, context(""));
        String help = String.join("\n", out);

        for (String name : List.of("signup", "login", "logout", "mkpwallet", "lw", "fetch", "train", "optimize",
                "models", "test", "backtest", "trade", "le", "lsa", "lsd", "lst", "ls", "start", "stop", "term",
                "exit", "ayuda")) {
            assertTrue(help.contains(name), "falta en la ayuda: " + name);
        }
        assertEquals(1, out.size());
    }
}
