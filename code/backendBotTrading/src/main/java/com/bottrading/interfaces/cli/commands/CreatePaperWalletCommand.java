package com.bottrading.interfaces.cli.commands;

import com.bottrading.interfaces.cli.CliCommandContext;
import com.bottrading.interfaces.cli.CliInputValidator;
import java.math.BigDecimal;

/**
 * Handles mkpwallet command.
 */
public final class CreatePaperWalletCommand implements CliCommand {

    @Override
    public String name() {
        return "mkpwallet";
    }

    @Override
    public void execute(String[] parts, CliCommandContext context) {
        if (!CliInputValidator.requireLogin(context)) {
            return;
        }

        if (!CliInputValidator.requireMinArgs(parts, 2, "Uso: mkpwallet <nombre>", context)) {
            return;
        }

        context.walletService().crearWallet(parts[1], new BigDecimal("10000.00"), false);
        context.println().accept("Wallet de papel '" + parts[1] + "' creada con 10,000 USD.");
    }
}
