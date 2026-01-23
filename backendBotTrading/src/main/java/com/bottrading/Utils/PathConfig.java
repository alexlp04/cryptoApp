package com.bottrading.utils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class PathConfig {
    // BACKEND_ROOT = .../cryptoApp/backendBotTrading
    public static final String PROJECT_ROOT = System.getProperty("user.dir");

    // Rutas a carpetas hermanas
    public static final String PYTHON_SCRIPTS_DIR = PROJECT_ROOT + File.separator + "scripts";
    public static final String STRATEGIES_DIR = PROJECT_ROOT + File.separator + "strategies";
    public static final String RESULTS_DIR = PROJECT_ROOT + File.separator + "results";

    // Rutas a los motores de ejecución
    public static final String ENGINE_RT_PATH = PYTHON_SCRIPTS_DIR + File.separator + "engine_rt.py";
    public static final String ENGINE_BACKTEST_PATH = PYTHON_SCRIPTS_DIR + File.separator + "engine_backtest.py";
    public static final String FETCHER_PATH = PYTHON_SCRIPTS_DIR + File.separator + "fetcher.py";
    public static final String INDICATORS_PATH = PYTHON_SCRIPTS_DIR + File.separator + "indicators.py";

    /**
     * Valida el nombre y devuelve la ruta absoluta de la estrategia.
     * Solo acepta nombres de archivo, no rutas.
     */
    public static String getValidStrategyPath(String nombreEntrada) throws Exception {
        File fileInput = new File(nombreEntrada);

        if (fileInput.isAbsolute() || nombreEntrada.contains("/") || nombreEntrada.contains("\\")
                || nombreEntrada.contains("..")) {
            throw new Exception("Error: Introduce el nombre del archivo.\n" +
                    "Las estrategias deben estar en: " + STRATEGIES_DIR);
        }

        String nombreLimpio = nombreEntrada.endsWith(".py") ? nombreEntrada : nombreEntrada + ".py";
        Path rutaFinal = Paths.get(STRATEGIES_DIR, nombreLimpio);
        if (!Files.exists(rutaFinal)) {
            throw new Exception("Error: No existe el archivo '" + nombreLimpio + "' en la carpeta /strategies/");
        }

        return rutaFinal.toString();
    }
}