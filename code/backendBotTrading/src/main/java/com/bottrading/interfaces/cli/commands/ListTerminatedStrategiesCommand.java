package com.bottrading.interfaces.cli.commands;

import com.bottrading.interfaces.cli.CliCommandContext;
import com.bottrading.interfaces.cli.CliInputValidator;

/**
 * Handles lst command.
 */
public final class ListTerminatedStrategiesCommand implements CliCommand {

    @Override
    public String name() {
        return "lst";
    }

    @Override
    public void execute(String[] parts, CliCommandContext context) {
        if (!CliInputValidator.requireLogin(context)) {
            return;
        }

        context.println().accept("--- Historial Terminadas ---");
        context.queryStrategiesUseCase().listarEstrategiasTerminadas().forEach(context.println());
    }
}
