package com.bottrading.strategy.infrastructure;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.bottrading.config.ProcessExecutorConfig;
import com.bottrading.market.domain.Timeframe;
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

    /** Código fijo: el nombre y las rutas llegan por argv, nunca interpolados (sin inyección de código). */
    private static final String INSPECT_CODE = "import os, sys, importlib.util\n"
            + "strategy_path, strategy_name, code_root = sys.argv[1], sys.argv[2], sys.argv[3]\n"
            + "if code_root not in sys.path:\n"
            + "    sys.path.insert(0, code_root)\n"
            + "spec = importlib.util.spec_from_file_location(strategy_name, strategy_path)\n"
            + "mod = importlib.util.module_from_spec(spec)\n"
            + "spec.loader.exec_module(mod)\n"
            + "print(getattr(mod, strategy_name)().get_warmup_period())\n";

    public static int getWarmupPeriod(String strategyName) throws Exception {
        if (strategyName == null || strategyName.isBlank()) {
            throw new IllegalArgumentException("strategyName no puede ser null/vacío");
        }

        String safeStrategyName = strategyName.trim();
        // Validar ANTES de ejecutar nada: rechaza '..', separadores y estrategias inexistentes.
        String strategyPath = PathConfig.getValidStrategyPath(safeStrategyName);
        String moduleName = new File(strategyPath).getName().replaceFirst("\\.py$", "");
        String codeRoot = new File(PathConfig.STRATEGIES_DIR).getParent();

        ProcessBuilder pb = new ProcessBuilder(List.of(
                PathConfig.resolvePythonExecutable(), "-c", INSPECT_CODE, strategyPath, moduleName, codeRoot));
        pb.directory(new File(PathConfig.PROJECT_ROOT));

        Process process = pb.start();
        // Se drenan ambos flujos mientras se espera: si el módulo imprime más de ~64 KB el hijo
        // se bloqueaba escribiendo y waitFor daba un falso timeout.
        CompletableFuture<String> out = CompletableFuture.supplyAsync(() -> readStream(process.getInputStream()));
        CompletableFuture<String> err = CompletableFuture.supplyAsync(() -> readStream(process.getErrorStream()));
        boolean finished = process.waitFor(ProcessExecutorConfig.TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            PythonProcessSupport.destroyForcibly(process, 2, TimeUnit.SECONDS);
            throw new TimeoutException("Timeout consultando warmup de estrategia: " + safeStrategyName);
        }

        String stdout = out.get(5, TimeUnit.SECONDS).trim();
        String stderr = err.get(5, TimeUnit.SECONDS).trim();
        int exitCode = process.exitValue();

        if (exitCode != 0) {
            String error = stderr.isBlank() ? stdout : stderr;
            throw new IllegalStateException("Error inspeccionando estrategia '" + safeStrategyName + "': " + error);
        }

        String[] lines = stdout.split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.matches("^-?\\d+$")) {
                return Integer.parseInt(line);
            }
        }

        throw new IllegalStateException("No se pudo parsear warmup de stdout: '" + stdout + "'");
    }

    public static int getCandlesRequired(String strategyName, String timeframe, int days) throws Exception {
        return getCandlesRequired(timeframe, days, getWarmupPeriod(strategyName));
    }

    /**
     * Variante para llamantes que ya han resuelto el warmup y no quieren pagar
     * un segundo arranque del interprete de Python.
     */
    public static int getCandlesRequired(String timeframe, int days, int warmup) {
        return (Timeframe.velasPorDiaOrDefault(timeframe) * days) + warmup;
    }

    private static String readStream(InputStream inputStream) {
        try (inputStream) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
