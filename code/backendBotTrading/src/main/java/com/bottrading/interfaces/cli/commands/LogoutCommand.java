package com.bottrading.interfaces.cli.commands;

import com.bottrading.interfaces.cli.CliCommandContext;

/**
 * Handles user logout from CLI.
 */
public final class LogoutCommand implements CliCommand {

    @Override
    public String name() {
        return "logout";
    }

    @Override
    public void execute(String[] parts, CliCommandContext context) {
        if (context.sessionManager().isLoggedIn()) {
            context.sessionManager().logout();
            context.println().accept("Sesion cerrada correctamente.");
            return;
        }

        context.println().accept("No hay ninguna sesion iniciada.");
    }
}
