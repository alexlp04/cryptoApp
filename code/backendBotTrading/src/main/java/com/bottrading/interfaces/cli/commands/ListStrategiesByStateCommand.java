package com.bottrading.interfaces.cli.commands;

import java.util.List;
import java.util.function.Function;

import com.bottrading.interfaces.cli.CliCommandContext;
import com.bottrading.interfaces.cli.CliInputValidator;
import com.bottrading.strategy.application.port.in.QueryStrategiesUseCase;

/**
 * Comando de listado de estrategias parametrizado por estado.
 * Sustituye a las cuatro variantes (ls, lsa, lsd, lst), que solo diferian
 * en el nombre, la cabecera y el metodo consultado.
 */
public record ListStrategiesByStateCommand(
        String name,
        String header,
        Function<QueryStrategiesUseCase, List<String>> query) implements CliCommand {

    public static ListStrategiesByStateCommand todas() {
        return new ListStrategiesByStateCommand(
                "ls", "--- Estrategias en Ejecucion ---", QueryStrategiesUseCase::listarEstrategias);
    }

    public static ListStrategiesByStateCommand activas() {
        return new ListStrategiesByStateCommand(
                "lsa", "--- Estrategias en Ejecucion ---", QueryStrategiesUseCase::listarEstrategiasActivas);
    }

    public static ListStrategiesByStateCommand detenidas() {
        return new ListStrategiesByStateCommand(
                "lsd", "--- Historial Detenidas ---", QueryStrategiesUseCase::listarEstrategiasDetenidas);
    }

    public static ListStrategiesByStateCommand terminadas() {
        return new ListStrategiesByStateCommand(
                "lst", "--- Historial Terminadas ---", QueryStrategiesUseCase::listarEstrategiasTerminadas);
    }

    @Override
    public void execute(String[] parts, CliCommandContext context) {
        if (!CliInputValidator.requireLogin(context)) {
            return;
        }

        context.println().accept(header);
        query.apply(context.queryStrategiesUseCase()).forEach(context.println());
    }
}
