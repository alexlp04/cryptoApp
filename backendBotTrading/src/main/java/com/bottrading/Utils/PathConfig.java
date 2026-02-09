package com.bottrading.utils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

// Importamos las constantes estáticas
import static com.bottrading.utils.AppConstants.*;

public final class PathConfig {

    private PathConfig() {
        throw new UnsupportedOperationException("Clase de utilidad, no instanciar.");
    }

    // Detectamos dinámicamente la raíz real del backend
    public static final String PROJECT_ROOT = calculateProjectRoot();

    // Definición de carpetas (Usando constantes de AppConstants)
    public static final String PYTHON_SCRIPTS_DIR = resolvePath(DIR_SCRIPTS);
    public static final String STRATEGIES_DIR = resolvePath(DIR_STRATEGIES);
    public static final String RESULTS_DIR = resolvePath(DIR_RESULTS);

    // Definición de motores
    public static final String ENGINE_RT_PATH = resolvePath(DIR_SCRIPTS, FILE_ENGINE_RT);
    public static final String ENGINE_BACKTEST_PATH = resolvePath(DIR_SCRIPTS, FILE_ENGINE_BACKTEST);
    public static final String FETCHER_PATH = resolvePath(DIR_SCRIPTS, FILE_FETCHER);
    public static final String INDICATORS_PATH = resolvePath(DIR_SCRIPTS, FILE_INDICATORS);

    private static String calculateProjectRoot() {
        String userDir = System.getProperty("user.dir");
        Path path = Paths.get(userDir);
        if (path.toString().contains(DIR_BACKEND)) {
            return path.getParent().toAbsolutePath().toString();
        }
        return userDir;
    }

    private static String resolvePath(String... parts) {
        return Paths.get(PROJECT_ROOT, parts).toString();
    }

    public static String getValidStrategyPath(String nombreEntrada) {
        if (nombreEntrada.contains("..") || nombreEntrada.contains("/") || nombreEntrada.contains("\\")) {
            throw new IllegalArgumentException("Nombre de archivo inválido por seguridad.");
        }
        
        String nombreLimpio = nombreEntrada.endsWith(EXTENSION_PYTHON) 
                ? nombreEntrada 
                : nombreEntrada + EXTENSION_PYTHON;
                
        Path rutaFinal = Paths.get(STRATEGIES_DIR, nombreLimpio);

        if (!Files.exists(rutaFinal)) {
            throw new IllegalArgumentException("No existe la estrategia '" + nombreLimpio + "' en: " + STRATEGIES_DIR);
        }
        return rutaFinal.toAbsolutePath().toString();
    }
}