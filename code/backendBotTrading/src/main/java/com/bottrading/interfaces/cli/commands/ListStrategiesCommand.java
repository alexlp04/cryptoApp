package com.bottrading.interfaces.cli.commands;

import com.bottrading.interfaces.cli.CliCommandContext;
import com.bottrading.interfaces.cli.CliInputValidator;

/**
 * Handles ls command.
 */
public final class ListStrategiesCommand implements CliCommand {

    @Override
    public String name() {
        return "ls";
    }

    @Override
    public void execute(String[] parts, CliCommandContext context) {
        if (!CliInputValidator.requireLogin(context)) {
            return;
        }

        context.println().accept("--- Estrategias en Ejecucion ---");
        context.queryStrategiesUseCase().listarEstrategias().forEach(context.println());
    }
}
