package com.bottrading.interfaces.cli.commands;

import java.math.BigDecimal;

import com.bottrading.interfaces.cli.CliCommandContext;
import com.bottrading.interfaces.cli.CliInputValidator;

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

        if (!CliInputValidator.requireMinArgs(parts, 2, "Uso: mkpwallet <nombre> [saldo]", context)) {
            return;
        }

        BigDecimal saldo = new BigDecimal("10000.00");
        if (parts.length >= 3) {
            try {
                saldo = new BigDecimal(parts[2]);
                if (saldo.compareTo(BigDecimal.ZERO) <= 0) {
                    context.println().accept("El saldo debe ser un valor positivo.");
                    return;
                }
            } catch (NumberFormatException e) {
                context.println().accept("Saldo inválido: '" + parts[2] + "'. Usa un número, por ejemplo: 5000.00");
                return;
            }
        }

        context.walletManagementUseCase().crearWallet(parts[1], saldo, false);
        context.println().accept("Wallet de papel '" + parts[1] + "' creada con " + saldo.toPlainString() + " USD.");
    }
}
