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
                 [DATOS]    fetch -tf <tf> -coins <coin1> [coin2...] [-d <dias>]
                            fetch <coin> <tf> [-d <dias>] [--detect-gaps]
                            cbi -tf <tf> -coins <coin>   (calcular indicadores)
                 [IA]       train -model <modelo> -tf <tf> -coins <coin> [-strategy <nombre>] [-d <dias>] [-params k=v,...]
                            optimize -model <modelo> -tf <tf> -coins <coin> [-strategy <nombre>] [-d <dias>]
                                     [-min-composite <1-100>] [-n-trials <n>] [-cv-folds <2-20>]
                            models, test
                 [TRADING]  backtest -strategy <nombre> [-model <nombre>] -tf <tf> -coins <coin1> [coin2...]
                            trade -v|-r -strategy <nombre> [-model <nombre>] -tf <tf> -coins <coin1> [coin2...]
                            le (scripts), lsa (activas), lsd (detenidas), lst (terminadas), ls (todas)
                            start <ID> | start -all   (reanudar)
                            stop <ID> | stop -all     (pausar, fondos en reserva)
                            term <ID>                 (liquidar y devolver fondos)
                 [SISTEMA]  exit, quit, ayuda
                -----------------------------------------------------
                """);
    }
}
