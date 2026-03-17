package com.bottrading.interfaces.cli.commands;

import com.bottrading.interfaces.cli.CliCommandContext;
import com.bottrading.interfaces.cli.CliInputValidator;
import com.bottrading.utils.CommandParser;

/**
 * Handles cbi command.
 */
public final class CbiCommand implements CliCommand {

    @Override
    public String name() {
        return "cbi";
    }

    @Override
    public void execute(String[] parts, CliCommandContext context) {
        CommandParser args = new CommandParser(parts);

        if (!CliInputValidator.validateParser(args, context)) {
            return;
        }

        if (!CliInputValidator.requireTimeframeAndCoins(args,
                "Uso: cbi -tf <timeframe> -coins <symbol> (Ej: cbi -tf 1h -coins BTCUSDT)", context)) {
            return;
        }

        String symbol = args.getCoins().get(0);
        context.marketDataService().calcularIndicadoresParaSimbolo(symbol, args.getTimeframe());
        context.println().accept("Datos de mercado e indicadores calculados para " + symbol);
    }
}
