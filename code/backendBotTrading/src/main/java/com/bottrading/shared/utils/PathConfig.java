package com.bottrading.shared.utils;

// Importamos las constantes estáticas
import static com.bottrading.shared.utils.AppConstants.DIR_MODELS;
import static com.bottrading.shared.utils.AppConstants.DIR_RESULTS;
import static com.bottrading.shared.utils.AppConstants.DIR_SCRIPTS;
import static com.bottrading.shared.utils.AppConstants.DIR_STRATEGIES;
import static com.bottrading.shared.utils.AppConstants.EXTENSION_PYTHON;
import static com.bottrading.shared.utils.AppConstants.FILE_ENGINE_AI_RT;
import static com.bottrading.shared.utils.AppConstants.FILE_ENGINE_BACKTEST;
import static com.bottrading.shared.utils.AppConstants.FILE_ENGINE_OPTIMIZE;
import static com.bottrading.shared.utils.AppConstants.FILE_ENGINE_RT;
import static com.bottrading.shared.utils.AppConstants.FILE_ENGINE_TRAIN;
import static com.bottrading.shared.utils.AppConstants.FILE_FETCHER;
import static com.bottrading.shared.utils.AppConstants.FILE_INDICATORS;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

import com.bottrading.shared.exceptions.FileOperationException;

public final class PathConfig {

    private PathConfig() {
        throw new UnsupportedOperationException("Clase de utilidad, no instanciar.");
    }

    /** Extensiones reconocidas para un modelo entrenado, en orden de preferencia. */
    private static final String[] MODEL_EXTENSIONS = {".pkl", ".keras", ".h5"};

    /**
     * Rechaza cualquier componente de ruta que permita escapar del directorio previsto.
     * Criterio único para estrategias y modelos.
     */
    private static boolean contieneTraversal(String... partes) {
        for (String parte : partes) {
            if (parte == null || parte.contains("..") || parte.contains("/") || parte.contains("\\")) {
                return true;
            }
        }
        return false;
    }

    // Detectamos dinámicamente la raíz real del proyecto (carpeta cryptoapp/)
    public static final String PROJECT_ROOT = calculateProjectRoot();
    
    // El código Python está en la carpeta "code/" dentro de PROJECT_ROOT
    public static final String CODE_DIR = resolvePath("code");

    // Definición de carpetas
    public static final String PYTHON_SCRIPTS_DIR = resolvePath("code", DIR_SCRIPTS);
    public static final String STRATEGIES_DIR = resolvePath("code", DIR_STRATEGIES);
    // results/ y models/ viven en la raíz del proyecto (mismo nivel que code/)
    public static final String RESULTS_DIR = resolvePath(DIR_RESULTS);
    public static final String MODELS_DIR = resolvePath(DIR_MODELS);

    // Definición de motores (dentro de code/scripts/)
    public static final String ENGINE_RT_PATH = resolvePath("code", DIR_SCRIPTS, FILE_ENGINE_RT);
    public static final String ENGINE_BACKTEST_PATH = resolvePath("code", DIR_SCRIPTS, FILE_ENGINE_BACKTEST);
    public static final String FETCHER_PATH = resolvePath("code", DIR_SCRIPTS, FILE_FETCHER);
    public static final String INDICATORS_PATH = resolvePath("code", DIR_SCRIPTS, FILE_INDICATORS);
    public static final String ENGINE_TRAIN_PATH = resolvePath("code", DIR_SCRIPTS, FILE_ENGINE_TRAIN);
    public static final String ENGINE_OPTIMIZE_PATH = resolvePath("code", DIR_SCRIPTS, FILE_ENGINE_OPTIMIZE);
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

    /** Añade la extensión .py si no la trae ya. */
    private static String conExtensionPython(String nombre) {
        return nombre.endsWith(EXTENSION_PYTHON) ? nombre : nombre + EXTENSION_PYTHON;
    }

    public static String getValidStrategyPath(String nombreEntrada) {
        if (contieneTraversal(nombreEntrada)) {
            throw new IllegalArgumentException("Nombre de archivo inválido por seguridad.");
        }

        String nombreLimpio = conExtensionPython(nombreEntrada);

        Path rutaFinal = Paths.get(STRATEGIES_DIR, nombreLimpio);

        if (!Files.exists(rutaFinal)) {
            throw new IllegalArgumentException("No existe la estrategia '" + nombreLimpio + "' en: " + STRATEGIES_DIR);
        }
        return rutaFinal.toAbsolutePath().toString();
    }

    /**
     * Localiza el fichero de un modelo entrenado siguiendo el mismo orden de precedencia
     * que usa {@code engine_ai_rt.load_model} en Python:
     * 1) nombre libre, 2) formato estándar {modelo}_{timeframe}_{symbol},
     * 3) variante con sufijo de estrategia.
     */
    private static Optional<String> findModelPath(String nombreModelo, String timeframe, String symbol) {
        Path modelDir = Paths.get(MODELS_DIR);

        // 1. Buscar por nombre libre (usuario ha renombrado el archivo)
        // 2. Buscar con formato estándar {modelo}_{timeframe}_{symbol}
        String baseName = String.format("%s_%s_%s", nombreModelo, timeframe, symbol);
        for (String name : new String[]{nombreModelo, baseName}) {
            for (String ext : MODEL_EXTENSIONS) {
                Path candidate = modelDir.resolve(name + ext);
                if (Files.exists(candidate)) {
                    return Optional.of(candidate.toAbsolutePath().toString());
                }
            }
        }

        // 3. Buscar variantes con sufijo de estrategia: {baseName}_{strategy}.ext
        try (var stream = Files.list(modelDir)) {
            return stream
                .filter(p -> esVarianteDeEstrategia(p, baseName))
                .findFirst()
                .map(p -> p.toAbsolutePath().toString());
        } catch (IOException e) {
            // Carpeta de modelos ilegible o inexistente: se trata como "no encontrado"
            return Optional.empty();
        }
    }

    private static boolean esVarianteDeEstrategia(Path candidate, String baseName) {
        String name = candidate.getFileName().toString();
        if (!name.startsWith(baseName + "_")) {
            return false;
        }
        for (String ext : MODEL_EXTENSIONS) {
            if (name.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    public static String getValidModelPath(String nombreModelo, String timeframe, String symbol) {
        if (contieneTraversal(nombreModelo, timeframe, symbol)) {
            throw new IllegalArgumentException("Nombre de modelo inválido por seguridad.");
        }

        return findModelPath(nombreModelo, timeframe, symbol).orElseThrow(() -> new IllegalArgumentException(
            "No existe el modelo '" + nombreModelo + "' para " + timeframe + ":" + symbol +
            " en la carpeta: " + MODELS_DIR + "\n" +
            "Buscado como: 1) nombre libre (.pkl/.keras/.h5), " +
            "2) formato estándar ({modelo}_{timeframe}_{symbol}), " +
            "3) variantes con sufijo de estrategia. " +
            "¿Has ejecutado el comando 'train'?"
        ));
    }

    /**
     * Comprueba si el script de Python de la estrategia existe.
     */
    public static boolean existeEstrategia(String nombreAlgoritmo) {
        return !contieneTraversal(nombreAlgoritmo)
                && Files.exists(Paths.get(STRATEGIES_DIR, conExtensionPython(nombreAlgoritmo)));
    }

    /**
     * Comprueba si el archivo del modelo entrenado existe para ese algoritmo, timeframe y símbolo.
     * Aplica exactamente los mismos criterios de búsqueda que {@link #getValidModelPath}.
     */
    public static boolean existeModelo(String modelo, String timeframe, String symbol) {
        return !contieneTraversal(modelo, timeframe, symbol)
                && findModelPath(modelo, timeframe, symbol).isPresent();
    }

    /**
     * Obtiene (creándola si hace falta) la carpeta de resultados de una estrategia.
     */
    public static Path getCarpetaEstrategia(String nombreEstrategia) {
        try {
            Path path = Paths.get(RESULTS_DIR, nombreEstrategia);
            if (!Files.exists(path)) {
                Files.createDirectories(path);
            }
            return path;
        } catch (IOException e) {
            throw new FileOperationException("Error al crear carpeta de estrategia: " + e.getMessage(), e);
        }
    }
}