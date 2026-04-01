package com.bottrading.interfaces.cli.commands;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

import com.bottrading.interfaces.cli.CliCommandContext;
import com.bottrading.interfaces.cli.CliInputValidator;
import com.bottrading.utils.CommandParser;

/**
 * Handles market data fetch command.
 */
public final class FetchCommand implements CliCommand {

    private static final DateTimeFormatter GAP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @Override
    public String name() {
        return "fetch";
    }

    @Override
    public void execute(String[] parts, CliCommandContext context) {
        CommandParser args = new CommandParser(parts);

        if (!CliInputValidator.validateParser(args, context)) {
            return;
        }

        completarDesdePosicionales(args);

        if (!CliInputValidator.requireTimeframeAndCoins(args,
                "Uso: fetch -tf <timeframe> -coins <coin1> [coin2...] | fetch <coin> <timeframe> [-d <days>] [--detect-gaps]", context)) {
            return;
        }

        if (args.isDetectGaps()) {
            ejecutarModoGapFill(args, context);
            return;
        }

        ejecutarModoFullRefresh(args, context);
    }

    private void ejecutarModoFullRefresh(CommandParser args, CliCommandContext context) {
        Integer days = args.getDays();
        if (days != null && days <= 0) {
            context.println().accept("El valor de -d/--days debe ser mayor que 0.");
            return;
        }

        for (String symbol : args.getCoins()) {
            context.println().accept("Full refresh para " + symbol + " [" + args.getTimeframe() + "]...");
            context.marketDataService().fullRefresh(symbol, args.getTimeframe(), days);
            context.println().accept("Sincronizacion completa para " + symbol + ".");
        }
    }

    private void ejecutarModoGapFill(CommandParser args, CliCommandContext context) {
        Integer days = args.getDays();
        if (days != null && days <= 0) {
            context.println().accept("El valor de -d/--days debe ser mayor que 0.");
            return;
        }

        LocalDateTime toDate = LocalDateTime.now(ZoneOffset.UTC);

        for (String symbol : args.getCoins()) {
            LocalDateTime fromDate;

            if (days != null) {
                fromDate = toDate.minusDays(days);
            } else {
                fromDate = context.marketDataService().findOldestTimestamp(symbol, args.getTimeframe());
                if (fromDate == null) {
                    context.println().accept("No hay datos existentes para " + symbol + " [" + args.getTimeframe()
                            + "] para detectar huecos.");
                    continue;
                }
            }

            List<Pair<LocalDateTime, LocalDateTime>> gaps = detectGaps(symbol, args.getTimeframe(), fromDate, toDate,
                    context);
            printGaps(symbol, args.getTimeframe(), gaps, context);

            for (Pair<LocalDateTime, LocalDateTime> gap : gaps) {
                context.marketDataService().fillGapRange(symbol, args.getTimeframe(), gap.left(), gap.right());
            }
        }
    }

    public List<Pair<LocalDateTime, LocalDateTime>> detectGaps(
            String symbol,
            String timeframe,
            LocalDateTime fromDate,
            LocalDateTime toDate,
            CliCommandContext context) {
        return context.fetchGapDetector().detectGaps(symbol, timeframe, fromDate, toDate);
    }

    private void completarDesdePosicionales(CommandParser args) {
        List<String> positional = args.getPositionalArgs();
        if (positional.size() < 2) {
            return;
        }

        if (args.getCoins().isEmpty()) {
            args.getCoins().add(positional.getFirst().toUpperCase());
        }

        if (args.getTimeframe() == null) {
            args.setTimeframe(positional.get(1));
        }
    }

    private void printGaps(
            String symbol,
            String timeframe,
            List<Pair<LocalDateTime, LocalDateTime>> gaps,
            CliCommandContext context) {
        context.println().accept("Gaps detected for " + symbol + " [" + timeframe + "]:");

        for (int i = 0; i < gaps.size(); i++) {
            Pair<LocalDateTime, LocalDateTime> gap = gaps.get(i);
            context.println().accept("[" + (i + 1) + "] "
                    + GAP_FORMATTER.format(gap.left()) + " → " + GAP_FORMATTER.format(gap.right()));
        }

        context.println().accept("Total: " + gaps.size() + " gap(s) found.");
    }
}
