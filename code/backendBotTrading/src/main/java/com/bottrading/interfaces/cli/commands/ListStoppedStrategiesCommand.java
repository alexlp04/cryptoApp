package com.bottrading.interfaces.cli.commands;

import com.bottrading.interfaces.cli.CliCommandContext;
import com.bottrading.interfaces.cli.CliInputValidator;

/**
 * Handles lsd command.
 */
public final class ListStoppedStrategiesCommand implements CliCommand {

    @Override
    public String name() {
        return "lsd";
    }

    @Override
    public void execute(String[] parts, CliCommandContext context) {
        if (!CliInputValidator.requireLogin(context)) {
            return;
        }

        context.println().accept("--- Historial Detenidas ---");
        context.queryStrategiesUseCase().listarEstrategiasDetenidas().forEach(context.println());
    }
}
