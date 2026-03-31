package com.bottrading.utils;

import com.bottrading.exceptions.EnvironmentConfigException;
import lombok.extern.slf4j.Slf4j;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Valida que el entorno Python esté disponible y correctamente configurado con
 * todas las dependencias necesarias (pandas, numpy, scikit-learn, etc.).
 * 
 * Se ejecuta en el startup de la aplicación Spring Boot para fallar rápido si
 * el entorno no está disponible.
 */
@Slf4j
public class PythonEnvironmentValidator {

    private static final int VALIDATION_TIMEOUT_SECONDS = 30;

    /**
     * Valida que Python está disponible y tiene las librerías requeridas.
     * Lanza EnvironmentConfigException si falla.
     */
    public static void validatePythonEnvironment() {
        
        String pythonExe = AppConstants.PYTHON_EXECUTABLE;
        File pythonFile = new File(pythonExe);
        
        if (!pythonFile.exists()) {
            String absolutePath = pythonFile.getAbsolutePath();
            throw new EnvironmentConfigException(
                "El ejecutable Python no se encuentra en: " + absolutePath + "\n" +
                "Verifica que:\n" +
                "  1. Existe el venv en: " + absolutePath + "\n" +
                "  2. La aplicación se ejecuta desde el directorio raíz del proyecto\n" +
                "  3. Si usas ruta relativa, asegúrate que apunta al venv correcto"
            );
        }
                
        validatePythonVersion(pythonExe);
        
        validateRequiredModules(pythonExe);
        }

    private static void validatePythonVersion(String pythonExe) {
        try {
            String output = runPythonCommand(pythonExe, 
                "-c", "import sys; print(f'{sys.version_info.major}.{sys.version_info.minor}')");
            
            String version = output.trim();
            
            String[] parts = version.split("\\.");
            if (parts.length < 2) {
                throw new EnvironmentConfigException("No se pudo parsear la versión de Python: " + version);
            }
            
            int major = Integer.parseInt(parts[0]);
            int minor = Integer.parseInt(parts[1]);
            
            if (major < 3 || (major == 3 && minor < 11)) {
                throw new EnvironmentConfigException(
                    "Python " + version + " no es compatible. Se requiere Python 3.11 o superior"
                );
            }
        } catch (IOException | InterruptedException e) {
            throw new EnvironmentConfigException(
                "Error al validar versión de Python: " + e.getMessage(), e
            );
        }
    }

    private static void validateRequiredModules(String pythonExe) {
        // Lista de módulos críticos que DEBEN estar instalados
        String[] requiredModules = {
            "pandas",
            "numpy",
            "sklearn",           // scikit-learn
            "xgboost",
            "lightgbm",
            "joblib",
            "tensorflow",
            "optuna"
        };
        
        StringBuilder missingModules = new StringBuilder();
        
        for (String module : requiredModules) {
            try {
                runPythonCommand(pythonExe, "-c", "import " + module);
                log.debug("  ✓ Módulo {} disponible", module);
            } catch (IOException | InterruptedException e) {
                missingModules.append("  - ").append(module).append("\n");
                log.warn("  ✗ Módulo {} NO encontrado", module);
            }
        }
        
        if (missingModules.length() > 0) {
            throw new EnvironmentConfigException(
                "Faltan módulos Python críticos:\n" + missingModules.toString() +
                "\nSolución: Ejecutar en el venv del proyecto:\n" +
                "  .venv/bin/pip install -r requirements.txt"
            );
        }
    }

    /**
     * Ejecuta un comando Python y retorna su stdout.
     * Si el comando falla, lanza IOException.
     */
    private static String runPythonCommand(String pythonExe, String... args) 
            throws IOException, InterruptedException {
        
        String[] command = new String[args.length + 1];
        command[0] = pythonExe;
        System.arraycopy(args, 0, command, 1, args.length);
        
        log.debug("Ejecutando: {}", String.join(" ", command));
        
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);  // Separar stderr
        
        Process process = pb.start();
        
        // Leer stdout
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }
        
        // Leer stderr en caso de error
        StringBuilder error = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                error.append(line).append("\n");
            }
        }
        
        // Esperar con timeout
        boolean finished = process.waitFor(VALIDATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Validación de Python excedió timeout de " + 
                VALIDATION_TIMEOUT_SECONDS + "s");
        }
        
        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new IOException("Python retornó código " + exitCode + ": " + error.toString());
        }
        
        return output.toString();
    }
}
