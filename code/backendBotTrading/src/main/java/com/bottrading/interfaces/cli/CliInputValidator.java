package com.bottrading.interfaces.cli;

import com.bottrading.market.domain.Timeframe;
import com.bottrading.shared.utils.CommandParser;
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

    public static boolean requireLoggedOut(CliCommandContext context) {
        if (context.sessionManager().isLoggedIn()) {
            String nombre = context.sessionManager().getCurrentUser().getNombre();
            context.println().accept("Ya hay una sesion activa (" + nombre + "). Haz 'logout' antes.");
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

    /**
     * Resuelve cuantos dias de historico usar para entrenar u optimizar.
     * Si no se pidieron dias, aplica el recomendado del timeframe; si se piden mas
     * del maximo, avisa y pide confirmacion.
     *
     * @return los dias a usar, o -1 si el usuario cancela.
     */
    public static int resolveTrainingDays(String timeframe, Integer requestedDays, CliCommandContext context) {
        Timeframe politica = Timeframe.politicaEntrenamiento(timeframe);

        if (requestedDays == null) {
            int recomendado = politica.diasEntrenamientoPorDefecto();
            context.println().accept("No se especificaron dias (-d). Usando valor recomendado para "
                    + timeframe + ": " + recomendado + " dias.");
            return recomendado;
        }

        int maxDias = politica.maxDiasEntrenamiento();
        if (requestedDays > maxDias) {
            context.println().accept("ADVERTENCIA: Para el timeframe " + timeframe
                    + ", el maximo recomendado es " + maxDias + " dias.");
            context.println().accept("Usar " + requestedDays
                    + " dias podria provocar un error de Memoria (Out Of Memory) y confundir a la IA.");
            if (!readYesNo(context, "Estas seguro de que quieres intentar continuar? (s/n): ")) {
                return -1;
            }
        }

        return requestedDays;
    }
}
