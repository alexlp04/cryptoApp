package com.bottrading.interfaces.cli.commands;

import com.bottrading.interfaces.cli.CliCommandContext;

/**
 * Prints CLI help text.
 */
public final class HelpCommand implements CliCommand {

    @Override
    public String name() {
        return "ayuda";
    }

    @Override
    public void execute(String[] parts, CliCommandContext context) {
        context.println().accept("""
                -----------------------------------------------------
                 COMANDOS DISPONIBLES
                -----------------------------------------------------
                 [USUARIO]  signup, login, logout
                 [WALLETS]  mkpwallet <nombre>, lw
                 [DATOS]    fetch <tf> <coin1> ...
                 [TRADING]  le (Listar scripts), lsa (Activas), lsd (Detenidas)
                            backtest <estra> <tf> <coins...>
                            trade -v|-r <estra> <tf> <coins...>
                            stop <ID> | stop -all
                            term <ID> (Liquidar y devolver fondos)
                 [SISTEMA]  exit, quit, ayuda
                -----------------------------------------------------
                """);
    }
}
