package com.bottrading.strategy.infrastructure;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.bottrading.config.ProcessExecutorConfig;
import com.bottrading.shared.utils.AppConstants;
import com.bottrading.shared.utils.PathConfig;
import com.bottrading.trading.infrastructure.bridge.PythonProcessSupport;

/**
 * Utilidad para inspeccionar metadata de estrategias Python sin acoplar Java
 * al código interno de cada estrategia.
 */
public final class StrategyInspector {

    private StrategyInspector() {
        throw new UnsupportedOperationException("Clase utilitaria, no instanciar");
    }

    public static int getWarmupPeriod(String strategyName) throws Exception {
        if (strategyName == null || strategyName.isBlank()) {
            throw new IllegalArgumentException("strategyName no puede ser null/vacío");
        }

        String safeStrategyName = strategyName.trim();
        String strategiesDir = PathConfig.STRATEGIES_DIR;
        String pythonCode = "import os, sys, importlib.util\n"
            + "strategy_name = '" + escapePythonLiteral(safeStrategyName) + "'\n"
            + "strategies_dir = '" + escapePythonLiteral(strategiesDir) + "'\n"
            + "code_root = os.path.dirname(strategies_dir)\n"
            + "if code_root not in sys.path:\n"
            + "    sys.path.insert(0, code_root)\n"
            + "strategy_path = os.path.join(strategies_dir, strategy_name + '.py')\n"
            + "if not os.path.exists(strategy_path):\n"
            + "    raise FileNotFoundError(f'Strategy file not found: {strategy_path}')\n"
            + "spec = importlib.util.spec_from_file_location(strategy_name, strategy_path)\n"
            + "mod = importlib.util.module_from_spec(spec)\n"
            + "spec.loader.exec_module(mod)\n"
            + "cls = getattr(mod, strategy_name)\n"
                + "inst = cls()\n"
                + "print(inst.get_warmup_period())\n";

        ProcessBuilder pb = new ProcessBuilder(List.of(
                AppConstants.PYTHON_EXECUTABLE,
                "-c",
                pythonCode));
        pb.directory(new File(PathConfig.PROJECT_ROOT));

        Process process = pb.start();
        boolean finished = process.waitFor(ProcessExecutorConfig.TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            PythonProcessSupport.destroyForcibly(process, 2, TimeUnit.SECONDS);
            throw new RuntimeException("Timeout consultando warmup de estrategia: " + safeStrategyName);
        }

        String stdout = readStream(process.getInputStream()).trim();
        String stderr = readStream(process.getErrorStream()).trim();
        int exitCode = process.exitValue();

        if (exitCode != 0) {
            String error = stderr.isBlank() ? stdout : stderr;
            throw new RuntimeException("Error inspeccionando estrategia '" + safeStrategyName + "': " + error);
        }

        String[] lines = stdout.split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.matches("^-?\\d+$")) {
                return Integer.parseInt(line);
            }
        }

        throw new RuntimeException("No se pudo parsear warmup de stdout: '" + stdout + "'");
    }

    /**
     * Variante para llamantes que ya han resuelto el warmup y no quieren pagar
     * un segundo arranque del interprete de Python.
     */
    public static int getCandlesRequired(String timeframe, int days, int warmup) {
        int candlesPerDay = switch (timeframe == null ? "" : timeframe.toLowerCase()) {
            case "1m" -> 1440;
            case "5m" -> 288;
            case "15m" -> 96;
            case "1h" -> 24;
            case "4h" -> 6;
            case "1d" -> 1;
            default -> 288;
        };
        return (candlesPerDay * days) + warmup;
    }

    public static int getCandlesRequired(String strategyName, String timeframe, int days) throws Exception {
        int warmup = getWarmupPeriod(strategyName);
        int candlesPerDay = switch (timeframe == null ? "" : timeframe.toLowerCase()) {
            case "1m" -> 1440;
            case "5m" -> 288;
            case "15m" -> 96;
            case "1h" -> 24;
            case "4h" -> 6;
            case "1d" -> 1;
            default -> 288;
        };
        return (candlesPerDay * days) + warmup;
    }

    private static String readStream(InputStream inputStream) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            return reader.lines().reduce("", (acc, line) -> acc.isEmpty() ? line : acc + "\n" + line);
        }
    }

    private static String escapePythonLiteral(String value) {
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }
}
