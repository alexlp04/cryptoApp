package com.bottrading.interfaces.cli.commands;

import com.bottrading.interfaces.cli.CliCommandContext;
import com.bottrading.interfaces.cli.CliInputValidator;
import com.bottrading.shared.utils.CommandParser;

/**
 * Comando CLI para la búsqueda de hiperparámetros óptimos mediante Optuna.
 * Reutiliza el mismo pipeline de preparación de datos que TrainCommand.
 */
public final class OptimizeCommand implements CliCommand {

    private static final double DEFAULT_MIN_ACCURACY = 55.0;

    @Override
    public String name() {
        return "optimize";
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
            context.println().accept("Uso: optimize -model <modelo> -tf <timeframe> -coins <coin> [-d <dias>] [-strategy <nombre>] [-min-accuracy <porcentaje>]");
            context.println().accept("Ejemplo: optimize -model xgboost -tf 1h -coins BTCUSDT -d 365 -strategy RSISMAStrategy -min-accuracy 60");
            return;
        }

        double minAccuracy = resolverMinAccuracy(args, context);
        if (minAccuracy < 0) {
            return;
        }

        String coin = args.getCoins().get(0);
        int diasEntrenamiento = validarYCalcularDias(args.getTimeframe(), args.getDays(), context);
        if (diasEntrenamiento == -1) {
            context.println().accept("Operacion cancelada.");
            return;
        }

        context.println().accept("Iniciando optimizacion de hiperparametros...");
        context.println().accept("Buscando configuracion optima para " + args.getModelo().toUpperCase()
                + " en " + coin + "/" + args.getTimeframe() + " (100 trials)...");

        String resultado = context.aiOptimizationService().optimizarHiperparametros(
                args.getModelo(),
                args.getTimeframe(),
                coin,
                diasEntrenamiento,
                args.getEstrategia(),
                minAccuracy);

        context.println().accept("\n--- RESULTADO OPTIMIZACION ---");
        context.println().accept(resultado);
        context.println().accept("------------------------------");
    }

    private double resolverMinAccuracy(CommandParser args, CliCommandContext context) {
        if (args.getMinAccuracy() == null) {
            context.println().accept("No se especifico -min-accuracy. Usando valor por defecto: "
                    + DEFAULT_MIN_ACCURACY + "%");
            return DEFAULT_MIN_ACCURACY;
        }
        double val = args.getMinAccuracy();
        if (val <= 0 || val > 100) {
            context.println().accept("Error: -min-accuracy debe estar entre 1 y 100.");
            return -1;
        }
        return val;
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
