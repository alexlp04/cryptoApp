package com.bottrading.interfaces.cli.commands;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

import com.bottrading.interfaces.cli.CliCommandContext;
import com.bottrading.interfaces.cli.CliInputValidator;
import com.bottrading.shared.utils.PathConfig;

import lombok.extern.slf4j.Slf4j;

/**
 * Comando CLI que ejecuta de forma automática y secuencial todos los comandos
 * definidos en el fichero {@code comandos.txt} ubicado en la raíz del proyecto.
 *
 * <p>Solo se procesan las líneas que empiezan por un nombre de comando registrado
 * (actualmente {@code optimize} y {@code backtest}). Las líneas vacías y los comentarios (#) se
 * ignoran. Cuando un comando falla, se registra el error y se continúa con el
 * siguiente.
 */
@Slf4j
public final class TestCommand implements CliCommand {

    private static final String COMANDOS_FILE = "comandos.txt";
    private static final String BACKTEST_TEST_INPUT = String.join("\n", "10000", "0.2", "n", "n", "");

    @Override
    public String name() {
        return "test";
    }

    @Override
    public void execute(String[] parts, CliCommandContext context) {
        if (!CliInputValidator.requireLogin(context)) {
            return;
        }

        Path rutaArchivo = Paths.get(PathConfig.PROJECT_ROOT, COMANDOS_FILE);

        if (!Files.exists(rutaArchivo)) {
            context.println().accept("Error: No se encontro el archivo " + rutaArchivo.toAbsolutePath());
            return;
        }

        List<String> lineas;
        try {
            lineas = Files.readAllLines(rutaArchivo);
        } catch (IOException e) {
            context.println().accept("Error leyendo " + COMANDOS_FILE + ": " + e.getMessage());
            log.error("[test] Error leyendo {}: {}", rutaArchivo, e.getMessage(), e);
            return;
        }

        List<String[]> comandos = parsearComandos(lineas);

        if (comandos.isEmpty()) {
            context.println().accept("No se encontraron comandos validos en " + COMANDOS_FILE);
            return;
        }

        int total = comandos.size();
        context.println().accept("=== TEST AUTOMATICO: " + total + " comandos encontrados en " + COMANDOS_FILE + " ===");

        int exitos = 0;
        int fallos = 0;

        for (int i = 0; i < total; i++) {
            String[] cmdParts = comandos.get(i);
            String lineaOriginal = String.join(" ", cmdParts);
            String nombreComando = cmdParts[0];

            context.println().accept("");
            context.println().accept(">>> [" + (i + 1) + "/" + total + "] " + lineaOriginal);

            try {
                if (!executeSupportedCommand(cmdParts, context)) {
                    context.println().accept("Comando '" + nombreComando + "' no soportado en test. Se omite.");
                    continue;
                }
                exitos++;
                context.println().accept("<<< [" + (i + 1) + "/" + total + "] OK");
            } catch (Exception e) {
                fallos++;
                context.println().accept("<<< [" + (i + 1) + "/" + total + "] ERROR: " + e.getMessage());
                log.error("[test] Fallo en comando '{}': {}", lineaOriginal, e.getMessage(), e);
            }
        }

        context.println().accept("");
        context.println().accept("=== TEST FINALIZADO: " + exitos + " exitos, " + fallos + " fallos de " + total + " ===");
    }

    private boolean executeSupportedCommand(String[] cmdParts, CliCommandContext context) {
        String nombreComando = cmdParts[0];
        switch (nombreComando) {
            case "optimize" -> {
                new OptimizeCommand().execute(cmdParts, context);
                return true;
            }
            case "backtest" -> {
                ejecutarBacktestConValoresFijos(cmdParts, context);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private void ejecutarBacktestConValoresFijos(String[] cmdParts, CliCommandContext context) {
        context.println().accept("[test] Backtest automatico con capital=10000, riesgo=0.2.");
        try (Scanner scanner = new Scanner(BACKTEST_TEST_INPUT)) {
            new BacktestCommand().execute(cmdParts, createContextWithScanner(context, scanner));
        }
    }

    private CliCommandContext createContextWithScanner(CliCommandContext context, Scanner scanner) {
        return new CliCommandContext(
                scanner,
                context.createUsuarioUseCase(),
                context.getUsuarioUseCase(),
                context.authenticateUseCase(),
                context.sessionManager(),
                context.strategyLifecycleUseCase(),
                context.queryStrategiesUseCase(),
                context.strategyCatalogUseCase(),
                context.executeBacktestUseCase(),
                context.walletManagementUseCase(),
                context.aiTrainingService(),
                context.aiOptimizationService(),
                context.marketDataService(),
                context.fetchMarketDataService(),
                context.fetchGapDetector(),
                context.print(),
                context.println());
    }

    private List<String[]> parsearComandos(List<String> lineas) {
        List<String[]> resultado = new ArrayList<>();
        for (String linea : lineas) {
            String limpia = linea.strip();
            if (limpia.isEmpty() || limpia.startsWith("#")) {
                continue;
            }
            String[] partes = limpia.split("\\s+");
            if (partes.length > 0) {
                resultado.add(partes);
            }
        }
        return resultado;
    }
}
