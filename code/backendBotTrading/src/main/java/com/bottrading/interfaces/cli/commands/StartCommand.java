package com.bottrading.interfaces.cli.commands;

import com.bottrading.interfaces.cli.CliCommandContext;
import com.bottrading.interfaces.cli.CliInputValidator;

/**
 * Handles start command for strategy lifecycle.
 */
public final class StartCommand implements CliCommand {

    @Override
    public String name() {
        return "start";
    }

    @Override
    public void execute(String[] parts, CliCommandContext context) {
        if (!CliInputValidator.requireLogin(context)) {
            return;
        }

        if (!CliInputValidator.requireMinArgs(parts, 2, "Uso: start <ID>  o  start -all", context)) {
            return;
        }

        if (parts[1].equalsIgnoreCase("-all")) {
            context.estrategiaService().iniciarTodasDetenidas();
            context.println().accept("Solicitud de inicio masivo enviada.");
            return;
        }

        Long id = CliInputValidator.parseLongId(parts[1], context);
        if (id == null) {
            return;
        }

        try {
            context.estrategiaService().iniciarEstrategiaDetenida(id);
            context.println().accept("Estrategia " + id + " iniciada.");
        } catch (Exception e) {
            context.println().accept("Error: " + e.getMessage());
        }
    }
}
