package com.bottrading.interfaces.cli.commands;

import com.bottrading.interfaces.cli.CliCommandContext;

/**
 * Handles le command.
 */
public final class ListStrategyFilesCommand implements CliCommand {

    @Override
    public String name() {
        return "le";
    }

    @Override
    public void execute(String[] parts, CliCommandContext context) {
        context.println().accept("--- Ficheros de Estrategias (.py) ---");
        var ficheros = context.strategyCatalogUseCase().listarFicherosDeEstrategias();
        if (ficheros.isEmpty()) {
            context.println().accept("No hay estrategias disponibles.");
            return;
        }
        ficheros.forEach(context.println());
    }
}
