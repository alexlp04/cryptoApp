package com.bottrading.interfaces.cli.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bottrading.interfaces.cli.CliCommandContext;
import com.bottrading.services.EstrategiaService;
import com.bottrading.services.SessionManager;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(MockitoExtension.class)
class StartCommandTest {

    @Mock
    private SessionManager sessionManager;

    @Mock
    private EstrategiaService estrategiaService;

    private final List<String> printedLines = new ArrayList<>();
    private CliCommandContext context;
    private StartCommand command;

    @BeforeEach
    void setUp() {
        context = new CliCommandContext(
                new Scanner(new StringReader("")),
                null,
                sessionManager,
                estrategiaService,
                null,
                null,
                null,
                s -> {
                },
                printedLines::add);
        command = new StartCommand();
        printedLines.clear();
    }

    @Test
    void executeRejectsWhenNotLoggedIn() {
        when(sessionManager.isLoggedIn()).thenReturn(false);

        command.execute(new String[] { "start", "1" }, context);

        verify(estrategiaService, never()).iniciarEstrategiaDetenida(anyLong());
        verify(estrategiaService, never()).iniciarTodasDetenidas();
        assertEquals("Acceso denegado. Debes hacer 'login' primero.", printedLines.getFirst());
    }

    @Test
    void executeStartsAllWhenAllFlagProvided() {
        when(sessionManager.isLoggedIn()).thenReturn(true);

        command.execute(new String[] { "start", "-all" }, context);

        verify(estrategiaService).iniciarTodasDetenidas();
        assertEquals("Solicitud de inicio masivo enviada.", printedLines.getFirst());
    }

    @Test
    void executePrintsErrorWhenIdIsInvalid() {
        when(sessionManager.isLoggedIn()).thenReturn(true);

        command.execute(new String[] { "start", "abc" }, context);

        verify(estrategiaService, never()).iniciarEstrategiaDetenida(anyLong());
        assertEquals("El ID debe ser un numero.", printedLines.getFirst());
    }

    @Test
    void executeStartsSpecificStrategyWhenIdIsValid() {
        when(sessionManager.isLoggedIn()).thenReturn(true);

        command.execute(new String[] { "start", "42" }, context);

        verify(estrategiaService).iniciarEstrategiaDetenida(42L);
        assertEquals("Estrategia 42 iniciada.", printedLines.getFirst());
    }
}
