package com.bottrading.interfaces.cli.commands;

import com.bottrading.interfaces.cli.CliCommandContext;
import com.bottrading.interfaces.cli.CliInputValidator;
import com.bottrading.utils.CommandParser;
import com.bottrading.utils.PathConfig;
import java.math.BigDecimal;

/**
 * Handles trade command orchestration from CLI.
 */
public final class TradeCommand implements CliCommand {

    @Override
    public String name() {
        return "trade";
    }

    @Override
    public void execute(String[] parts, CliCommandContext context) {
        if (!CliInputValidator.requireLogin(context)) {
            return;
        }

        CommandParser args = new CommandParser(parts);

        if (!CliInputValidator.validateParser(args, context)) {
            return;
        }

        if ((!args.isReal() && !args.isVirtual()) || (args.isReal() && args.isVirtual())
                || args.getTimeframe() == null || args.getCoins().isEmpty()
                || (args.getEstrategia() == null && args.getModelo() == null)) {
            context.println().accept(
                    "Uso correcto: trade -v|-r [-strategy <nombre>] [-model <nombre>] -tf <timeframe> -coins <coin1> [coin2...]");
            return;
        }

        if (args.isReal()) {
            context.println().accept("ADVERTENCIA: Has seleccionado MODO REAL. Asegurate de tener fondos y entender los riesgos.");
        }

        if (args.getModelo() != null && args.getEstrategia() == null) {
            context.println().accept("Error: -model requiere tambien -strategy para calcular indicadores.");
            context.println().accept("Uso: trade -v -model <nombre> -strategy <estrategia> -tf <tf> -coins <coin1> [coin2...]");
            return;
        }

        if (args.getEstrategia() != null && !PathConfig.existeEstrategia(args.getEstrategia())) {
            context.println().accept("Error: No se encuentra el script de estrategia '" + args.getEstrategia() + ".py'.");
            return;
        }

        if (args.getModelo() != null) {
            for (String coin : args.getCoins()) {
                if (!PathConfig.existeModelo(args.getModelo(), args.getTimeframe(), coin)) {
                    context.println().accept("Error: No se encuentra el modelo '" + args.getModelo()
                            + "' para " + coin + " en timeframe " + args.getTimeframe() + ".");
                    context.println().accept("Pista: Ejecuta primero -> train -model " + args.getModelo()
                            + " -strategy " + args.getEstrategia()
                            + " -tf " + args.getTimeframe() + " -coins " + coin);
                    return;
                }
            }
        }

        context.println().accept("\nSelecciona una wallet:");
        context.walletService().listarWallets().forEach(context.println());

        context.print().accept("Nombre exacto de la wallet: ");
        String walletName = context.scanner().nextLine().trim();

        BigDecimal balanceTotal = context.walletService().getBalance(walletName);
        Long walletId = context.walletService().obtenerIdPorNombre(walletName);
        BigDecimal comprometido = context.estrategiaService().getCapitalComprometido(walletId);
        BigDecimal disponible = balanceTotal.subtract(comprometido);

        context.println().accept(String.format("Saldo Total: %s | En Silos: %s | DISPONIBLE: %s",
                balanceTotal, comprometido, disponible));

        BigDecimal capitalAsignado = CliInputValidator.readBigDecimal(context,
            "Capital a asignar a este bot: ", "capital asignado");
        if (capitalAsignado == null) {
            return;
        }

        if (capitalAsignado.compareTo(disponible) > 0) {
            throw new IllegalArgumentException("Saldo insuficiente en la wallet.");
        }

        BigDecimal risk = CliInputValidator.readBigDecimal(context, "Riesgo por trade (0.01 - 1.0): ", "riesgo");
        if (risk == null) {
            return;
        }

        context.estrategiaService().iniciarTradeRT(args.getEstrategia(), args.getModelo(), args.getTimeframe(),
                args.getCoins(), args.isReal(), walletId, risk, capitalAsignado);
        context.println().accept("Bot lanzado en segundo plano con exito.");
    }
}
