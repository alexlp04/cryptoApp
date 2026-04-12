package com.bottrading.interfaces.cli.commands;

import com.bottrading.interfaces.cli.CliCommandContext;
import com.bottrading.interfaces.cli.CliInputValidator;

/**
 * Handles stop command for strategy lifecycle.
 */
public final class StopCommand implements CliCommand {

    @Override
    public String name() {
        return "stop";
    }

    @Override
    public void execute(String[] parts, CliCommandContext context) {
        if (!CliInputValidator.requireLogin(context)) {
            return;
        }

        if (!CliInputValidator.requireMinArgs(parts, 2, "Uso: stop <ID>  o  stop -all", context)) {
            return;
        }

        if (parts[1].equalsIgnoreCase("-all")) {
            context.strategyLifecycleUseCase().detenerTodas();
            context.println().accept("Todas las estrategias activas han sido pausadas.");
            return;
        }

        Long id = CliInputValidator.parseLongId(parts[1], context);
        if (id == null) {
            return;
        }

        context.strategyLifecycleUseCase().detenerEstrategia(id);
        context.println().accept("Estrategia " + id + " detenida.");
    }
}
