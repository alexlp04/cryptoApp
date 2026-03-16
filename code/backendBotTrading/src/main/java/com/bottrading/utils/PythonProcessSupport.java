package com.bottrading.utils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Utilidades comunes para ejecutar procesos Python de forma consistente.
 */
public final class PythonProcessSupport {

    private PythonProcessSupport() {
    }

    public static Process startPythonScript(String scriptPath, boolean redirectErrorStream, String... args)
            throws IOException {
        List<String> command = new ArrayList<>();
        command.add(AppConstants.PYTHON_EXECUTABLE);
        command.add(scriptPath);
        if (args != null) {
            for (String arg : args) {
                if (arg != null && !arg.isBlank()) {
                    command.add(arg);
                }
            }
        }

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(redirectErrorStream);
        return pb.start();
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
