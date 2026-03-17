package com.bottrading.interfaces.cli;

import com.bottrading.utils.CommandParser;
import java.math.BigDecimal;

/**
 * Shared validation helpers for CLI commands.
 */
public final class CliInputValidator {

    private CliInputValidator() {
    }

    public static boolean requireLogin(CliCommandContext context) {
        if (!context.sessionManager().isLoggedIn()) {
            context.println().accept("Acceso denegado. Debes hacer 'login' primero.");
            return false;
        }
        return true;
    }

    public static boolean requireMinArgs(String[] parts, int minLength, String usage, CliCommandContext context) {
        if (parts.length < minLength) {
            context.println().accept(usage);
            return false;
        }
        return true;
    }

    public static boolean validateParser(CommandParser args, CliCommandContext context) {
        if (args.hasErrorSintaxis()) {
            context.println().accept(args.getMensajeError());
            return false;
        }
        return true;
    }

    public static boolean requireTimeframeAndCoins(CommandParser args, String usage, CliCommandContext context) {
        if (args.getTimeframe() == null || args.getCoins().isEmpty()) {
            context.println().accept(usage);
            return false;
        }
        return true;
    }

    public static boolean requireStrategyTimeframeAndCoins(CommandParser args, String usage, CliCommandContext context) {
        if (args.getEstrategia() == null || args.getTimeframe() == null || args.getCoins().isEmpty()) {
            context.println().accept(usage);
            return false;
        }
        return true;
    }

    public static Long parseLongId(String rawValue, CliCommandContext context) {
        try {
            return Long.parseLong(rawValue);
        } catch (NumberFormatException e) {
            context.println().accept("El ID debe ser un numero.");
            return null;
        }
    }

    public static BigDecimal readBigDecimal(CliCommandContext context, String prompt, String fieldLabel) {
        context.print().accept(prompt);
        String rawValue = context.scanner().nextLine().trim();
        try {
            return new BigDecimal(rawValue);
        } catch (NumberFormatException e) {
            context.println().accept("Valor invalido para " + fieldLabel + ": '" + rawValue + "'.");
            return null;
        }
    }

    public static boolean readYesNo(CliCommandContext context, String prompt) {
        context.print().accept(prompt);
        return context.scanner().nextLine().trim().toLowerCase().startsWith("s");
    }
}
