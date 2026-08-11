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
        int diasEntrenamiento = CliInputValidator.resolveTrainingDays(
                args.getTimeframe(), args.getDays(), context);
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

}
