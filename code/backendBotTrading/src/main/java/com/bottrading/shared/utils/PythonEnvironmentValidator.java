package com.bottrading.shared.utils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.bottrading.shared.exceptions.EnvironmentConfigException;

import lombok.extern.slf4j.Slf4j;

/**
 * Valida que el entorno Python esté disponible y tenga las dependencias necesarias
 * (pandas, numpy, scikit-learn, etc.).
 *
 * Se ejecuta en el startup de la aplicación para fallar rápido si el entorno no está disponible.
 */
@Slf4j
public class PythonEnvironmentValidator {

    private static final int VALIDATION_TIMEOUT_SECONDS = 60;

    /** Módulos que DEBEN estar instalados (nombre de import). */
    private static final List<String> REQUIRED_MODULES = List.of(
            "pandas", "numpy", "sklearn", "xgboost", "lightgbm", "joblib", "optuna");

    /**
     * Módulos que pueden faltar: tensorflow solo lo necesitan los modelos Keras, y en una Raspberry Pi (arm64) puede no
     * haber rueda de la versión fijada. Sin él arranca todo salvo cargar modelos Keras.
     */
    private static final List<String> OPTIONAL_MODULES = List.of("tensorflow");

    /**
     * Un único proceso que comprueba versión y módulos con find_spec, sin importarlos
     * (importar tensorflow cuesta varios segundos). Imprime "major minor", los ausentes obligatorios y los ausentes
     * opcionales (una línea cada uno, argumentos separados por comas).
     */
    static final String CHECK_SCRIPT =
            "import importlib.util as u,sys;"
            + "print(sys.version_info[0],sys.version_info[1]);"
            + "[print(','.join(m for m in g.split(',') if u.find_spec(m) is None)) for g in sys.argv[1:3]]";

    private PythonEnvironmentValidator() {
    }

    /**
     * Valida que Python está disponible y tiene las librerías requeridas.
     * Lanza EnvironmentConfigException si falla.
     */
    public static void validatePythonEnvironment() {
        // La misma resolución que usa el runtime (contra PROJECT_ROOT, con fallback al PATH):
        // antes el validador buscaba la ruta relativa al directorio de trabajo de la JVM.
        validate(PathConfig.resolvePythonExecutable(), VALIDATION_TIMEOUT_SECONDS);
    }

    static void validate(String pythonExe, int timeoutSeconds) {
        String output = runPython(pythonExe, timeoutSeconds);
        String[] lines = output.strip().split("\\R", -1);
        String[] version = lines[0].trim().split("\\s+");
        if (version.length < 2) {
            throw new EnvironmentConfigException("No se pudo leer la versión de Python. Salida: " + output);
        }
        int major;
        int minor;
        try {
            major = Integer.parseInt(version[0]);
            minor = Integer.parseInt(version[1]);
        } catch (NumberFormatException e) {
            throw new EnvironmentConfigException("No se pudo parsear la versión de Python: " + lines[0], e);
        }
        if (major < 3 || (major == 3 && minor < 11)) {
            throw new EnvironmentConfigException(
                    "Python " + major + "." + minor + " no es compatible. Se requiere Python 3.11 o superior");
        }

        String missing = lines.length > 1 ? lines[1].trim() : "";
        String missingOptional = lines.length > 2 ? lines[2].trim() : "";
        if (!missingOptional.isEmpty()) {
            log.warn("Faltan módulos Python opcionales: {}. No se podrán cargar los modelos que los necesiten (Keras).",
                    missingOptional.replace(",", ", "));
        }
        if (!missing.isEmpty()) {
            throw new EnvironmentConfigException(
                    "Faltan módulos Python críticos: " + missing.replace(",", ", ") + "\n"
                    + "Solución: ejecutar en el venv del proyecto:\n"
                    + "  " + pythonExe + " -m pip install -r requirements.txt");
        }
        log.debug("Entorno Python {}.{} validado ({} módulos)", major, minor, REQUIRED_MODULES.size());
    }

    private static String runPython(String pythonExe, int timeoutSeconds) {
        List<String> command = new java.util.ArrayList<>(List.of(pythonExe, "-c", CHECK_SCRIPT));
        command.add(String.join(",", REQUIRED_MODULES));
        command.add(String.join(",", OPTIONAL_MODULES));

        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException e) {
            throw new EnvironmentConfigException(
                    "No se pudo ejecutar Python (" + pythonExe + "): " + e.getMessage() + "\n"
                    + "Verifica que existe el venv o que python3 está en el PATH.", e);
        }

        // stdout+stderr en un solo flujo que se drena en paralelo: sin deadlock por una tubería llena
        // y el timeout sí se aplica aunque el proceso se cuelgue.
        CompletableFuture<String> salida = CompletableFuture.supplyAsync(() -> leer(process.getInputStream()));
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new EnvironmentConfigException(
                        "La validación de Python excedió el timeout de " + timeoutSeconds + "s");
            }
            String output = salida.get(5, TimeUnit.SECONDS);
            if (process.exitValue() != 0) {
                throw new EnvironmentConfigException(
                        "Python terminó con código " + process.exitValue() + ": " + output.strip());
            }
            return output;
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new EnvironmentConfigException("Validación de Python interrumpida", e);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            process.destroyForcibly();
            throw new EnvironmentConfigException("No se pudo leer la salida de Python: " + e.getMessage(), e);
        }
    }

    private static String leer(InputStream in) {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
