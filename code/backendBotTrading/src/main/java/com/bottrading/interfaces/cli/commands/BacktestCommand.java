package com.bottrading.interfaces.cli.commands;

import com.bottrading.interfaces.cli.CliCommandContext;
import com.bottrading.interfaces.cli.CliInputValidator;
import com.bottrading.utils.CommandParser;
import java.math.BigDecimal;

/**
 * Handles backtest command.
 */
public final class BacktestCommand implements CliCommand {

    @Override
    public String name() {
        return "backtest";
    }

    @Override
    public void execute(String[] parts, CliCommandContext context) {
        CommandParser args = new CommandParser(parts);

        if (!CliInputValidator.validateParser(args, context)) {
            return;
        }

        if (!CliInputValidator.requireStrategyTimeframeAndCoins(args,
                "Uso: backtest -strategy <nombre> -tf <timeframe> -coins <coin1> [coin2...]", context)) {
            return;
        }

        BigDecimal capitalAsignado = CliInputValidator.readBigDecimal(context, "Capital a asignar: ", "capital");
        if (capitalAsignado == null) {
            return;
        }

        BigDecimal risk = CliInputValidator.readBigDecimal(context, "Riesgo por trade (0.01 - 1.0): ", "riesgo");
        if (risk == null) {
            return;
        }

        boolean limpiarBacktestsPrevios = CliInputValidator.readYesNo(context,
                "Eliminar archivos de backtest previos? (s/n): ");

        boolean guardarTrades = CliInputValidator.readYesNo(context, "Guardar trades del backtest en CSV? (s/n): ");

        context.println().accept("Iniciando Backtest...");
        try {
            context.estrategiaService().ejecutarBacktest(
                    args.getEstrategia(),
                    args.getTimeframe(),
                    args.getCoins(),
                    capitalAsignado,
                    risk,
                    limpiarBacktestsPrevios,
                    guardarTrades);
            context.println().accept("Backtest finalizado. Resultados guardados en CSV.");
        } catch (Exception e) {
            context.println().accept("Error: " + e.getMessage());
        }
    }
}
