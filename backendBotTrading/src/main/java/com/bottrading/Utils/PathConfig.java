package com.bottrading.utils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class PathConfig {

    // Detectamos dinámicamente la raíz real del backend
    public static final String PROJECT_ROOT = calculateProjectRoot();

    private static String calculateProjectRoot() {
        String userDir = System.getProperty("user.dir");
        Path path = Paths.get(userDir);

        // CASO 1: Ejecutando desde VS Code (carpeta raíz cryptoApp)
        if (Files.exists(path.resolve("backendBotTrading")) && Files.isDirectory(path.resolve("backendBotTrading"))) {
            return path.resolve("backendBotTrading").toAbsolutePath().toString();
        }

        // CASO 2: Ejecutando desde Maven dentro de backendBotTrading
        return userDir;
    }

    // Definición de carpetas (Siempre relativas a la raíz calculada arriba)
    public static final String PYTHON_SCRIPTS_DIR = resolvePath("scripts");
    public static final String STRATEGIES_DIR = resolvePath("strategies");
    public static final String RESULTS_DIR = resolvePath("results");

    // Definición de motores
    public static final String ENGINE_RT_PATH = resolvePath("scripts", "engine_rt.py");
    public static final String ENGINE_BACKTEST_PATH = resolvePath("scripts", "engine_backtest.py");
    public static final String FETCHER_PATH = resolvePath("scripts", "fetcher.py");
    public static final String INDICATORS_PATH = resolvePath("scripts", "indicators.py");

    private static String resolvePath(String... parts) {
        return Paths.get(PROJECT_ROOT, parts).toString();
    }

    public static String getValidStrategyPath(String nombreEntrada) {
        if (nombreEntrada.contains("..") || nombreEntrada.contains("/") || nombreEntrada.contains("\\")) {
            throw new RuntimeException("Nombre inválido.");
        }
        String nombreLimpio = nombreEntrada.endsWith(".py") ? nombreEntrada : nombreEntrada + ".py";
        Path rutaFinal = Paths.get(STRATEGIES_DIR, nombreLimpio);

        if (!Files.exists(rutaFinal)) {
            throw new RuntimeException("No existe el archivo '" + nombreLimpio + "' en: " + STRATEGIES_DIR);
        }
        return rutaFinal.toAbsolutePath().toString();
    }
}