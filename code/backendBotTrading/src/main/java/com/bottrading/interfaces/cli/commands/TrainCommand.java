package com.bottrading.interfaces.cli.commands;

import com.bottrading.interfaces.cli.CliCommandContext;
import com.bottrading.interfaces.cli.CliInputValidator;
import com.bottrading.shared.utils.CommandParser;

/**
 * Handles train command orchestration from CLI.
 */
public final class TrainCommand implements CliCommand {

    @Override
    public String name() {
        return "train";
    }

    @Override
    public void execute(String[] parts, CliCommandContext context) {
        if (!CliInputValidator.requireLogin(context)) {
            return;
        }

        CommandParser args = new CommandParser(parts);

        if (!CliInputValidator.validateParser(args, context)) {
            return;
        }

        if (args.getModelo() == null || args.getTimeframe() == null || args.getCoins().isEmpty()) {
            context.println().accept("Uso: train -model <modelo> -tf <timeframe> -coins <coin> [-strategy <nombre>]");
            context.println().accept("Ejemplo: train -model random_forest -tf 1h -coins BTCUSDT -strategy RSISMAStrategy");
            return;
        }

        String coin = args.getCoins().get(0);
        int diasEntrenamiento = validarYCalcularDias(args.getTimeframe(), args.getDays(), context);
        if (diasEntrenamiento == -1) {
            context.println().accept("Operacion cancelada.");
            return;
        }

        context.println().accept("Iniciando pipeline de Inteligencia Artificial...");
        String resultado = context.aiTrainingService().entrenarModelo(
                args.getModelo(),
                args.getTimeframe(),
                coin,
                diasEntrenamiento,
                args.getHyperparams(),
                args.getEstrategia());

        context.println().accept("\n--- RESULTADOS DEL MODELO ---");
        context.println().accept(resultado);
        context.println().accept("-----------------------------");
    }

    private int validarYCalcularDias(String timeframe, Integer requestedDays, CliCommandContext context) {
        int maxDays;
        int defaultDays;

        switch (timeframe.toLowerCase()) {
            case "1m" -> {
                maxDays = 180;
                defaultDays = 30;
            }
            case "5m" -> {
                maxDays = 365;
                defaultDays = 90;
            }
            case "15m" -> {
                maxDays = 730;
                defaultDays = 180;
            }
            case "1h" -> {
                maxDays = 1095;
                defaultDays = 365;
            }
            case "4h" -> {
                maxDays = 1825;
                defaultDays = 730;
            }
            case "1d" -> {
                maxDays = 3650;
                defaultDays = 1095;
            }
            default -> {
                maxDays = 365;
                defaultDays = 90;
            }
        }

        if (requestedDays == null) {
            context.println().accept("No se especificaron dias (-d). Usando valor recomendado para "
                    + timeframe + ": " + defaultDays + " dias.");
            return defaultDays;
        }

        if (requestedDays > maxDays) {
            context.println().accept("ADVERTENCIA: Para el timeframe " + timeframe
                    + ", el maximo recomendado es " + maxDays + " dias.");
            context.println().accept("Usar " + requestedDays
                    + " dias podria provocar un error de Memoria (Out Of Memory) y confundir a la IA.");
            if (!CliInputValidator.readYesNo(context, "Estas seguro de que quieres intentar continuar? (s/n): ")) {
                return -1;
            }
        }

        return requestedDays;
    }
}
