package com.bottrading.interfaces.cli.commands;

import com.bottrading.interfaces.cli.CliCommandContext;
import com.bottrading.interfaces.cli.CliInputValidator;

/**
 * Handles lsa command.
 */
public final class ListActiveStrategiesCommand implements CliCommand {

    @Override
    public String name() {
        return "lsa";
    }

    @Override
    public void execute(String[] parts, CliCommandContext context) {
        if (!CliInputValidator.requireLogin(context)) {
            return;
        }

        context.println().accept("--- Estrategias en Ejecucion ---");
        context.estrategiaService().listarEstrategiasActivas().forEach(context.println());
    }
}
