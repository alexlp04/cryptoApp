package com.bottrading.interfaces.cli.commands;

import com.bottrading.interfaces.cli.CliCommandContext;
import com.bottrading.interfaces.cli.CliInputValidator;

/**
 * Handles term command for strategy liquidation.
 */
public final class TermCommand implements CliCommand {

    @Override
    public String name() {
        return "term";
    }

    @Override
    public void execute(String[] parts, CliCommandContext context) {
        if (!CliInputValidator.requireLogin(context)) {
            return;
        }

        if (!CliInputValidator.requireMinArgs(parts, 2, "Uso: term <ID>  o  term -all (Cuidado: Liquida todo)",
                context)) {
            return;
        }

        if (parts[1].equalsIgnoreCase("-all")) {
            if (CliInputValidator.readYesNo(context, "Seguro que quieres LIQUIDAR TODAS las estrategias? (s/n): ")) {
                context.estrategiaService().terminarTodas();
                context.println().accept("Todas las estrategias han sido liquidadas.");
            } else {
                context.println().accept("Operacion cancelada.");
            }
            return;
        }

        Long id = CliInputValidator.parseLongId(parts[1], context);
        if (id == null) {
            return;
        }

        try {
            context.estrategiaService().terminarEstrategia(id);
            context.println().accept("Estrategia " + id + " liquidada.");
        } catch (Exception e) {
            context.println().accept("Error critico: " + e.getMessage());
        }
    }
}
