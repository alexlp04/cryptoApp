package com.bottrading.interfaces.cli.commands;

import com.bottrading.interfaces.cli.CliCommandContext;
import com.bottrading.interfaces.cli.CliInputValidator;
import com.bottrading.utils.CommandParser;

/**
 * Handles market data fetch command.
 */
public final class FetchCommand implements CliCommand {

    @Override
    public String name() {
        return "fetch";
    }

    @Override
    public void execute(String[] parts, CliCommandContext context) {
        CommandParser args = new CommandParser(parts);

        if (!CliInputValidator.validateParser(args, context)) {
            return;
        }

        if (!CliInputValidator.requireTimeframeAndCoins(args,
                "Uso: fetch -tf <timeframe> -coins <coin1> [coin2...]", context)) {
            return;
        }

        context.println().accept("Descargando datos... (Esto puede tardar)");
        context.marketDataService().actualizarDatosMercado(args.getCoins(), args.getTimeframe());
        context.println().accept("Sincronizacion completa.");
    }
}
