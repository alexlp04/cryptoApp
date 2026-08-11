package com.bottrading.interfaces.cli.commands;

import com.bottrading.interfaces.cli.CliCommandContext;
import com.bottrading.interfaces.cli.CliInputValidator;
import com.bottrading.shared.utils.CommandParser;

/**
 * Comando CLI para la búsqueda de hiperparámetros óptimos mediante Optuna.
 * Reutiliza el mismo pipeline de preparación de datos que TrainCommand.
 */
public final class OptimizeCommand implements CliCommand {

    private static final double DEFAULT_MIN_COMPOSITE = 55.0;
    private static final int DEFAULT_N_TRIALS = 100;
    private static final int DEFAULT_CV_FOLDS = 5;

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
            context.println().accept("Uso: optimize -model <modelo> -tf <timeframe> -coins <coin> [-d <dias>] [-strategy <nombre>] [-min-composite <porcentaje>] [-n-trials <numero>] [-cv-folds <numero>]");
            context.println().accept("Ejemplo: optimize -model xgboost -tf 1h -coins BTCUSDT -d 365 -strategy RSISMAStrategy -min-composite 60 -n-trials 100 -cv-folds 5");
            return;
        }

        double minComposite = resolverMinComposite(args, context);
        if (minComposite < 0) {
            return;
        }

        int nTrials = args.getNTrials() != null ? args.getNTrials() : DEFAULT_N_TRIALS;
        int cvFolds = args.getCvFolds() != null ? args.getCvFolds() : DEFAULT_CV_FOLDS;

        String coin = args.getCoins().get(0);
        int diasEntrenamiento = CliInputValidator.resolveTrainingDays(
                args.getTimeframe(), args.getDays(), context);
        if (diasEntrenamiento == -1) {
            context.println().accept("Operacion cancelada.");
            return;
        }

        context.println().accept("Iniciando optimizacion de hiperparametros...");
        context.println().accept("Buscando configuracion optima para " + args.getModelo().toUpperCase()
                + " en " + coin + "/" + args.getTimeframe() + " (" + nTrials + " trials, " + cvFolds + " folds)...");

        String resultado = context.aiOptimizationService().optimizarHiperparametros(
                args.getModelo(),
                args.getTimeframe(),
                coin,
                diasEntrenamiento,
                args.getEstrategia(),
                minComposite,
                nTrials,
                cvFolds);

        context.println().accept("\n--- RESULTADO OPTIMIZACION ---");
        context.println().accept(resultado);
        context.println().accept("------------------------------");
    }

    private double resolverMinComposite(CommandParser args, CliCommandContext context) {
        if (args.getMinComposite() == null) {
            context.println().accept("No se especifico -min-composite. Usando valor por defecto: "
                    + DEFAULT_MIN_COMPOSITE + "%");
            return DEFAULT_MIN_COMPOSITE;
        }
        double val = args.getMinComposite();
        if (val <= 0 || val > 100) {
            context.println().accept("Error: -min-composite debe estar entre 1 y 100.");
            return -1;
        }
        return val;
    }

}
