package com.bottrading.interfaces.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bottrading.services.SessionManager;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class CliInputValidatorTest {

    @Test
    void requireLoginReturnsFalseAndPrintsMessageWhenSessionClosed() {
        SessionManager sessionManager = Mockito.mock(SessionManager.class);
        Mockito.when(sessionManager.isLoggedIn()).thenReturn(false);

        List<String> printed = new ArrayList<>();
        CliCommandContext context = new CliCommandContext(
                new Scanner(new StringReader("")),
                null,
                sessionManager,
                null,
                null,
                null,
                null,
                s -> {
                },
                printed::add);

        boolean result = CliInputValidator.requireLogin(context);

        assertFalse(result);
        assertEquals(1, printed.size());
        assertEquals("Acceso denegado. Debes hacer 'login' primero.", printed.getFirst());
    }

    @Test
    void requireMinArgsReturnsFalseAndPrintsUsage() {
        List<String> printed = new ArrayList<>();
        CliCommandContext context = new CliCommandContext(
                new Scanner(new StringReader("")),
                null,
                Mockito.mock(SessionManager.class),
                null,
                null,
                null,
                null,
                s -> {
                },
                printed::add);

        boolean result = CliInputValidator.requireMinArgs(new String[] { "start" }, 2,
                "Uso: start <ID>  o  start -all", context);

        assertFalse(result);
        assertEquals("Uso: start <ID>  o  start -all", printed.getFirst());
    }

    @Test
    void parseLongIdReturnsNullAndPrintsMessageForInvalidId() {
        List<String> printed = new ArrayList<>();
        CliCommandContext context = new CliCommandContext(
                new Scanner(new StringReader("")),
                null,
                Mockito.mock(SessionManager.class),
                null,
                null,
                null,
                null,
                s -> {
                },
                printed::add);

        Long id = CliInputValidator.parseLongId("abc", context);

        assertNull(id);
        assertEquals("El ID debe ser un numero.", printed.getFirst());
    }

    @Test
    void readBigDecimalReturnsValueForValidInput() {
        List<String> prompts = new ArrayList<>();
        List<String> printed = new ArrayList<>();

        CliCommandContext context = new CliCommandContext(
                new Scanner(new StringReader("123.45\n")),
                null,
                Mockito.mock(SessionManager.class),
                null,
                null,
                null,
                null,
                prompts::add,
                printed::add);

        BigDecimal value = CliInputValidator.readBigDecimal(context, "Capital: ", "capital");

        assertEquals(new BigDecimal("123.45"), value);
        assertEquals("Capital: ", prompts.getFirst());
        assertTrue(printed.isEmpty());
    }

    @Test
    void readBigDecimalReturnsNullForInvalidInput() {
        List<String> prompts = new ArrayList<>();
        List<String> printed = new ArrayList<>();

        CliCommandContext context = new CliCommandContext(
                new Scanner(new StringReader("NaN\n")),
                null,
                Mockito.mock(SessionManager.class),
                null,
                null,
                null,
                null,
                prompts::add,
                printed::add);

        BigDecimal value = CliInputValidator.readBigDecimal(context, "Capital: ", "capital");

        assertNull(value);
        assertEquals("Capital: ", prompts.getFirst());
        assertEquals("Valor invalido para capital: 'NaN'.", printed.getFirst());
    }

    @Test
    void readYesNoReturnsTrueForYesPrefix() {
        List<String> prompts = new ArrayList<>();
        CliCommandContext context = new CliCommandContext(
                new Scanner(new StringReader("si\n")),
                null,
                Mockito.mock(SessionManager.class),
                null,
                null,
                null,
                null,
                prompts::add,
                s -> {
                });

        boolean value = CliInputValidator.readYesNo(context, "Confirmar? ");

        assertTrue(value);
        assertEquals("Confirmar? ", prompts.getFirst());
    }
}
