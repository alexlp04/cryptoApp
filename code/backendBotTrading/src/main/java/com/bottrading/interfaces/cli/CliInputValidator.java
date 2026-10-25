package com.bottrading.interfaces.cli;

import com.bottrading.market.domain.Timeframe;
import com.bottrading.shared.utils.CommandParser;
import java.math.BigDecimal;
import java.util.stream.Collectors;
import java.util.Arrays;

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

    /** train y optimize trabajan con una sola moneda: más de una se rechaza en vez de ignorar el resto. */
    public static boolean requireSingleCoin(CommandParser args, String comando, CliCommandContext context) {
        String error = errorMonedaUnica(args.getCoins(), comando);
        if (error != null) {
            context.println().accept(error);
        }
        return error == null;
    }

    static String errorMonedaUnica(java.util.List<String> coins, String comando) {
        if (coins.size() <= 1) {
            return null;
        }
        return comando + " trabaja con una sola moneda y has indicado " + coins.size() + " (" + String.join(", ", coins)
                + "). Lánzalo una vez por moneda.";
    }

    public static boolean requireTimeframeAndCoins(CommandParser args, String usage, CliCommandContext context) {
        if (args.getTimeframe() == null || args.getCoins().isEmpty()) {
            context.println().accept(usage);
            return false;
        }
        return timeframeSoportado(args.getTimeframe(), context);
    }

    /** Rechaza en la frontera un timeframe que Java no sabe medir (Python acepta más: 30m, 2h, 1w...). */
    static boolean timeframeSoportado(String timeframe, CliCommandContext context) {
        if (Timeframe.buscar(timeframe).isEmpty()) {
            context.println().accept("Timeframe no soportado: '" + timeframe + "'. Usa uno de: "
                    + Arrays.stream(Timeframe.values()).map(Timeframe::codigo).collect(Collectors.joining(", ")));
            return false;
        }
        return true;
    }

    public static boolean requireStrategyTimeframeAndCoins(CommandParser args, String usage, CliCommandContext context) {
        if (args.getEstrategia() == null || args.getTimeframe() == null || args.getCoins().isEmpty()) {
            context.println().accept(usage);
            return false;
        }
        if (!timeframeSoportado(args.getTimeframe(), context)) {
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

    /** Importe estrictamente positivo; null (con aviso) si no lo es. */
    public static BigDecimal readPositiveBigDecimal(CliCommandContext context, String prompt, String fieldLabel) {
        BigDecimal value = readBigDecimal(context, prompt, fieldLabel);
        if (value != null && value.signum() <= 0) {
            context.println().accept("El valor de " + fieldLabel + " debe ser mayor que cero.");
            return null;
        }
        return value;
    }

    /** Riesgo por trade en (0, 1]; el prompt dice 0.01-1.0 pero antes no se comprobaba. */
    public static BigDecimal readRiskPerTrade(CliCommandContext context, String prompt) {
        BigDecimal value = readBigDecimal(context, prompt, "riesgo");
        if (value != null && !isValidRisk(value)) {
            context.println().accept("El riesgo debe estar entre 0 (exclusivo) y 1.0.");
            return null;
        }
        return value;
    }

    static boolean isValidRisk(BigDecimal risk) {
        return risk != null && risk.signum() > 0 && risk.compareTo(BigDecimal.ONE) <= 0;
    }

    /**
     * Lee la contraseña sin eco cuando hay terminal; sin ella (tests, docker sin -it) usa el scanner.
     * No se hace trim: los espacios son parte de la contraseña.
     */
    public static String readPassword(CliCommandContext context, String prompt) {
        java.io.Console console = System.console();
        if (console != null) {
            char[] chars = console.readPassword("%s", prompt);
            return chars == null ? "" : new String(chars);
        }
        context.print().accept(prompt);
        return context.scanner().nextLine();
    }

    /** Exige escribir una palabra exacta (mayúsculas incluidas) para acciones con dinero real. */
    public static boolean readConfirmation(CliCommandContext context, String prompt, String expected) {
        context.print().accept(prompt);
        return expected.equals(context.scanner().nextLine().trim());
    }

    public static boolean readYesNo(CliCommandContext context, String prompt) {
        context.print().accept(prompt);
        String answer = context.scanner().nextLine().trim().toLowerCase(java.util.Locale.ROOT);
        return answer.equals("s") || answer.equals("si") || answer.equals("sí"); // "sudo" o "salir" ya no cuentan
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
