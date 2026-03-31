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

    // Detectamos dinámicamente la raíz real del proyecto (carpeta cryptoapp/)
    public static final String PROJECT_ROOT = calculateProjectRoot();
    
    // El código Python está en la carpeta "code/" dentro de PROJECT_ROOT
    public static final String CODE_DIR = resolvePath("code");

    // Definición de carpetas (dentro de code/)
    public static final String PYTHON_SCRIPTS_DIR = resolvePath("code", DIR_SCRIPTS);
    public static final String STRATEGIES_DIR = resolvePath("code", DIR_STRATEGIES);
    public static final String RESULTS_DIR = resolvePath("code", DIR_RESULTS);

    // Definición de motores (dentro de code/scripts/)
    public static final String ENGINE_RT_PATH = resolvePath("code", DIR_SCRIPTS, FILE_ENGINE_RT);
    public static final String ENGINE_BACKTEST_PATH = resolvePath("code", DIR_SCRIPTS, FILE_ENGINE_BACKTEST);
    public static final String FETCHER_PATH = resolvePath("code", DIR_SCRIPTS, FILE_FETCHER);
    public static final String INDICATORS_PATH = resolvePath("code", DIR_SCRIPTS, FILE_INDICATORS);
    public static final String ENGINE_TRAIN_PATH = resolvePath("code", DIR_SCRIPTS, FILE_ENGINE_TRAIN);
    public static final String MODELS_DIR = resolvePath("code", "models");
    public static final String ENGINE_AI_RT_PATH = resolvePath("code", DIR_SCRIPTS, FILE_ENGINE_AI_RT);

    private static String calculateProjectRoot() {
        String userDir = System.getProperty("user.dir");
        Path path = Paths.get(userDir);
        
        // Búsqueda recursiva hacia arriba hasta encontrar la carpeta que contiene "code/backendBotTrading"
        while (path != null) {
            // Si el parent directo contiene la carpeta "backendBotTrading", hemos encontrado la raíz
            Path codeBackendPath = path.resolve("code").resolve("backendBotTrading");
            if (Files.isDirectory(codeBackendPath)) {
                return path.toAbsolutePath().toString();
            }
            
            path = path.getParent();
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

    public static String getValidModelPath(String nombreModelo, String timeframe, String symbol) {
        String nombreArchivo = String.format("%s_%s_%s.pkl", nombreModelo, timeframe, symbol);

        if (nombreArchivo.contains("..") || nombreArchivo.contains("/") || nombreArchivo.contains("\\")) {
            throw new IllegalArgumentException("Nombre de modelo inválido por seguridad.");
        }

        Path rutaFinal = Paths.get(MODELS_DIR, nombreArchivo);

        if (!Files.exists(rutaFinal)) {
            throw new IllegalArgumentException("No existe el modelo de IA '" + nombreArchivo + "' en la carpeta: " + MODELS_DIR + ". ¿Has ejecutado el comando 'train'?");
        }
        
        return rutaFinal.toAbsolutePath().toString();
    }

    /**
     * Comprueba si el script de Python de la estrategia existe.
     */
    public static boolean existeEstrategia(String nombreAlgoritmo) {
        if (nombreAlgoritmo.contains("..") || nombreAlgoritmo.contains("/") || nombreAlgoritmo.contains("\\")) {
            return false;
        }
        String nombreLimpio = nombreAlgoritmo.endsWith(EXTENSION_PYTHON)
                ? nombreAlgoritmo
                : nombreAlgoritmo + EXTENSION_PYTHON;

        return Files.exists(Paths.get(STRATEGIES_DIR, nombreLimpio));
    }

    /**
     * Comprueba si el archivo del modelo entrenado existe para ese algoritmo, timeframe y símbolo.
     * Busca los formatos .pkl, .keras y .h5, incluyendo modelos con sufijo de estrategia.
     */
    public static boolean existeModelo(String modelo, String timeframe, String symbol) {
        if (modelo.contains("..") || modelo.contains("/") || modelo.contains("\\") ||
            timeframe.contains("..") || symbol.contains("..")) {
            return false;
        }
        // 0. Aceptar nombre libre: el usuario ha renombrado el fichero directamente
        Path dir = Paths.get(MODELS_DIR);
        for (String ext : new String[]{".pkl", ".keras", ".h5"}) {
            if (Files.exists(dir.resolve(modelo + ext))) return true;
        }
        // 1+2. Construcción estándar {modelo}_{timeframe}_{symbol} + variante con sufijo de estrategia
        String baseName = String.format("%s_%s_%s", modelo, timeframe, symbol);
        // Verificar nombre exacto con las extensiones conocidas
        for (String ext : new String[]{".pkl", ".keras", ".h5"}) {
            if (Files.exists(dir.resolve(baseName + ext))) return true;
        }
        // También aceptar variantes con sufijo de estrategia: baseName_{strategy}.{ext}
        try (var stream = Files.list(dir)) {
            return stream.anyMatch(p -> {
                String name = p.getFileName().toString();
                return name.startsWith(baseName + "_") &&
                       (name.endsWith(".pkl") || name.endsWith(".keras") || name.endsWith(".h5"));
            });
        } catch (Exception e) {
            return false;
        }
    }
}