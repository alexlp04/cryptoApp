package com.bottrading.interfaces.cli.commands;

import com.bottrading.interfaces.cli.CliCommandContext;
import com.bottrading.interfaces.cli.CliInputValidator;

/**
 * Handles lw command.
 */
public final class ListWalletsCommand implements CliCommand {

    @Override
    public String name() {
        return "lw";
    }

    @Override
    public void execute(String[] parts, CliCommandContext context) {
        if (!CliInputValidator.requireLogin(context)) {
            return;
        }

        context.println().accept("--- Tus Billeteras ---");
        context.walletManagementUseCase().listarWallets().forEach(context.println());
    }
}
