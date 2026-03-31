package com.bottrading.utils;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Utilidades comunes para ejecutar procesos Python de forma consistente.
 * 
 * Características:
 * - Resuelve rutas relativas a absolutas usando le directorio raíz del proyecto
 * - Maneja rutas dentro de venv
 * - Ejecuta procesos con configuración estándar (UTF-8, etc.)
 */
public final class PythonProcessSupport {

    private PythonProcessSupport() {
    }

    /**
     * Inicia un script Python resolviendo automáticamente las rutas relativas.
     * 
     * @param scriptPath Ruta relativa o absoluta al script Python
     * @param redirectErrorStream Si verdadero, stderr se redirige a stdout
     * @param args Argumentos para el script
     * @return Proceso iniciado
     * @throws IOException Si el proceso no se puede iniciar
     */
    public static Process startPythonScript(String scriptPath, boolean redirectErrorStream, String... args)
            throws IOException {
        
        String pythonExeAbsolute = resolvePythonExecutable();
        
        String scriptPathAbsolute = resolveScriptPath(scriptPath);
        
        List<String> command = new ArrayList<>();
        command.add(pythonExeAbsolute);
        command.add(scriptPathAbsolute);
        if (args != null) {
            for (String arg : args) {
                if (arg != null && !arg.isBlank()) {
                    command.add(arg);
                }
            }
        }

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(redirectErrorStream);
        
        // Ejecutar desde el directorio raíz del proyecto para que las rutas relativas funcionen
        pb.directory(new File(PathConfig.PROJECT_ROOT));
        
        return pb.start();
    }

    /**
     * Resuelve la ruta absoluta del ejecutable Python.
     * Si es ruta relativa (e.g., ".venv/bin/python3"), se resuelve respecto al PROJECT_ROOT.
     */
    private static String resolvePythonExecutable() throws IOException {
        String pythonExe = AppConstants.PYTHON_EXECUTABLE;
        File pythonFile = new File(pythonExe);
        
        // Si ya es ruta absoluta y existe, usarla
        if (pythonFile.isAbsolute() && pythonFile.exists()) {
            return pythonExe;
        }
        
        // Si es ruta relativa, resolver respecto a PROJECT_ROOT
        File resolvedFile = Paths.get(PathConfig.PROJECT_ROOT, pythonExe).toFile();
        if (resolvedFile.exists()) {
            return resolvedFile.getAbsolutePath();
        }
        
        // Fallback: intentar usar el ejecutable directamente (podría estar en PATH global)
        // Esto permite que funcione si se recupera a "python3" en AppConstants
        return pythonExe;
    }

    /**
     * Resuelve la ruta absoluta del script Python.
     * Si es ruta relativa, se resuelve respecto a PROJECT_ROOT.
     */
    private static String resolveScriptPath(String scriptPath) throws IOException {
        File scriptFile = new File(scriptPath);
        
        // Si ya es ruta absoluta y existe, usarla
        if (scriptFile.isAbsolute() && scriptFile.exists()) {
            return scriptPath;
        }
        
        // Si es ruta relativa y existe como tal, dejarla (ProcessBuilder.directory resolverá)
        if (scriptFile.exists()) {
            return scriptFile.getAbsolutePath();
        }
        
        // Si es ruta relativa, resolver respecto a PROJECT_ROOT
        File resolvedFile = Paths.get(PathConfig.PROJECT_ROOT, scriptPath).toFile();
        if (resolvedFile.exists()) {
            return resolvedFile.getAbsolutePath();
        }
        
        // Si no existe, lanzar error explícito
        throw new IOException(
            "Script Python no encontrado: " + scriptPath + "\n" +
            "Búsqueda realizada en: " + resolvedFile.getAbsolutePath()
        );
    }

    public static Future<Void> drainLinesAsync(InputStream inputStream,
            ExecutorService executor,
            Consumer<String> onLine,
            Consumer<IOException> onError) {
        return executor.submit(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (Thread.currentThread().isInterrupted()) {
                        break;
                    }
                    if (onLine != null) {
                        onLine.accept(line);
                    }
                }
            } catch (IOException e) {
                if (onError != null) {
                    onError.accept(e);
                }
            }
            return null;
        });
    }

    public static void writeUtf8(OutputStream outputStream, String payload) throws IOException {
        outputStream.write(payload.getBytes(StandardCharsets.UTF_8));
        outputStream.flush();
    }

    public static boolean waitFor(Process process, long timeout, TimeUnit unit) throws InterruptedException {
        return process.waitFor(timeout, unit);
    }

    public static void destroyForcibly(Process process, long waitTime, TimeUnit unit) {
        if (process == null || !process.isAlive()) {
            return;
        }

        process.destroyForcibly();
        try {
            process.waitFor(waitTime, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
