package com.bottrading.bridge;

import com.bottrading.utils.PythonProcessSupport;
import jakarta.annotation.PreDestroy;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Fachada unificada para ejecución de procesos Python con timeout, retries,
 * lectura de stderr y cleanup consistente.
 */
@Slf4j
@Service
public class PythonBridgeFacade {

    private static final long PROCESS_POLL_INTERVAL_MS = 1000L;

    private final ExecutorService ioExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private static final class StderrCollector {
        private final StringBuilder buffer = new StringBuilder();

        synchronized void append(String line) {
            if (!buffer.isEmpty()) {
                buffer.append('\n');
            }
            buffer.append(line);
        }

        synchronized String text() {
            return buffer.toString().trim();
        }
    }

    private static final class ActivityMonitor {
        private final long startTimeMs = System.currentTimeMillis();
        private final AtomicLong lastActivityMs = new AtomicLong(startTimeMs);
        private final AtomicBoolean firstActivitySeen = new AtomicBoolean(false);

        void markActivity() {
            long now = System.currentTimeMillis();
            lastActivityMs.set(now);
            firstActivitySeen.compareAndSet(false, true);
        }

        long startTimeMs() {
            return startTimeMs;
        }

        long lastActivityMs() {
            return lastActivityMs.get();
        }

        boolean hasFirstActivity() {
            return firstActivitySeen.get();
        }
    }

    public <T> T execute(PythonBridgeRequest<T> request) throws PythonBridgeExecutionException {
        Exception lastError = null;

        for (int attempt = 1; attempt <= request.maxRetries(); attempt++) {
            Process process = null;
            Future<Void> stderrFuture = null;
            StderrCollector stderrCollector = new StderrCollector();
            ActivityMonitor activityMonitor = new ActivityMonitor();

            try {
                process = startProcess(
                    request.operationName(),
                    request.scriptPath(),
                    request.redirectErrorStream(),
                    request.args());

                if (!request.redirectErrorStream()) {
                    stderrFuture = startStderrDrain(request, process, stderrCollector, activityMonitor);
                }

                writeStdinIfPresent(request, process);
                Future<T> stdoutFuture = readStdoutAsyncIfPresent(request, process);

                waitForProcessCompletion(request, process, activityMonitor);

                T result = getStdoutResult(request, stdoutFuture);

                int exitCode = process.exitValue();
                if (request.failOnNonZeroExit() && exitCode != 0) {
                    throw new PythonBridgeExecutionException(buildExitCodeMessage(request, exitCode, stderrCollector));
                }

                return result;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new PythonBridgeExecutionException(
                        "Operación '" + request.operationName() + "' interrumpida", e);
            } catch (Exception e) {
                lastError = e;
                logAttemptFailure(request, attempt, e);
            } finally {
                if (stderrFuture != null) {
                    stderrFuture.cancel(true);
                }
                if (process != null && process.isAlive()) {
                    PythonProcessSupport.destroyForcibly(process, 2, TimeUnit.SECONDS);
                }
            }

            sleepBeforeRetry(request, attempt);
        }

        throw new PythonBridgeExecutionException(
                "Operación '" + request.operationName() + "' falló tras "
                        + request.maxRetries() + " intento(s)",
                lastError);
    }

    public Process startProcess(String operationName, String scriptPath, boolean redirectErrorStream, List<String> args)
            throws PythonBridgeExecutionException {
        try {
            return PythonProcessSupport.startPythonScript(
                    scriptPath,
                    redirectErrorStream,
                    args == null ? new String[0] : args.toArray(new String[0]));
        } catch (IOException e) {
            throw new PythonBridgeExecutionException(
                    "No se pudo iniciar proceso Python para '" + operationName + "': " + e.getMessage(),
                    e);
        }
    }

    public Future<Void> drainStderrAsync(Process process, Consumer<String> onLine) {
        return PythonProcessSupport.drainLinesAsync(
                process.getErrorStream(),
                ioExecutor,
                onLine,
                e -> log.debug("Finalizada lectura stderr con aviso: {}", e.getMessage()));
    }

    public void destroyProcess(Process process) {
        if (process != null && process.isAlive()) {
            PythonProcessSupport.destroyForcibly(process, 2, TimeUnit.SECONDS);
        }
    }

    private <T> Future<Void> startStderrDrain(
            PythonBridgeRequest<T> request,
            Process process,
            StderrCollector stderrCollector,
            ActivityMonitor activityMonitor) {
        return PythonProcessSupport.drainLinesAsync(
                process.getErrorStream(),
                ioExecutor,
                line -> {
                    activityMonitor.markActivity();
                    stderrCollector.append(line);
                    if (request.onStderrLine() != null) {
                        request.onStderrLine().accept(line);
                    }
                },
                e -> log.debug("Finalizada lectura stderr en '{}': {}", request.operationName(), e.getMessage()));
    }

    private <T> void writeStdinIfPresent(PythonBridgeRequest<T> request, Process process) throws Exception {
        if (request.stdinWriter() == null) {
            return;
        }
        try (OutputStream os = process.getOutputStream()) {
            request.stdinWriter().accept(os);
        }
    }

    private <T> Future<T> readStdoutAsyncIfPresent(PythonBridgeRequest<T> request, Process process) {
        if (request.stdoutReader() == null) {
            return null;
        }
        return ioExecutor.submit(() -> {
            try (InputStream is = process.getInputStream()) {
                return request.stdoutReader().apply(is);
            }
        });
    }

    private <T> T getStdoutResult(PythonBridgeRequest<T> request, Future<T> stdoutFuture)
            throws PythonBridgeExecutionException {
        if (stdoutFuture == null) {
            return null;
        }
        try {
            if (!request.hasTimeout()) {
                return stdoutFuture.get();
            }
            long readTimeout = Math.max(1L, request.timeout());
            return stdoutFuture.get(readTimeout, request.timeoutUnit());
        } catch (TimeoutException e) {
            stdoutFuture.cancel(true);
            throw new PythonBridgeExecutionException(
                    "Timeout leyendo stdout en operación '" + request.operationName() + "'", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PythonBridgeExecutionException(
                    "Lectura stdout interrumpida en operación '" + request.operationName() + "'", e);
        } catch (Exception e) {
            throw new PythonBridgeExecutionException(
                    "Error leyendo stdout en operación '" + request.operationName() + "': " + e.getMessage(),
                    e);
        }
    }

    private <T> void sleepBeforeRetry(PythonBridgeRequest<T> request, int attempt) throws PythonBridgeExecutionException {
        if (attempt >= request.maxRetries() || request.retryDelayMs() <= 0L) {
            return;
        }
        try {
            Thread.sleep(request.retryDelayMs());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PythonBridgeExecutionException(
                    "Interrumpido durante backoff de reintento en '" + request.operationName() + "'", e);
        }
    }

    private <T> void waitForProcessCompletion(
            PythonBridgeRequest<T> request,
            Process process,
            ActivityMonitor activityMonitor) throws InterruptedException, PythonBridgeExecutionException {
        if (!request.hasTimeout() && !request.hasStartupTimeout() && !request.hasInactivityTimeout()) {
            process.waitFor();
            return;
        }

        while (true) {
            if (process.waitFor(PROCESS_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)) {
                return;
            }
            validateProcessTimeouts(request, activityMonitor);
        }
    }

    private <T> void validateProcessTimeouts(PythonBridgeRequest<T> request, ActivityMonitor activityMonitor)
            throws PythonBridgeExecutionException {
        long now = System.currentTimeMillis();

        if (request.hasTimeout()) {
            long totalTimeoutMs = request.timeoutUnit().toMillis(request.timeout());
            if (now - activityMonitor.startTimeMs() > totalTimeoutMs) {
                throw new PythonBridgeExecutionException(
                        "Timeout total en operación '" + request.operationName() + "' tras "
                                + request.timeout() + " " + request.timeoutUnit().name().toLowerCase());
            }
        }

        if (!activityMonitor.hasFirstActivity() && request.hasStartupTimeout()) {
            long startupTimeoutMs = request.startupTimeoutUnit().toMillis(request.startupTimeout());
            if (now - activityMonitor.startTimeMs() > startupTimeoutMs) {
                throw new PythonBridgeExecutionException(
                        "Timeout de inicialización en operación '" + request.operationName() + "' tras "
                                + request.startupTimeout() + " " + request.startupTimeoutUnit().name().toLowerCase());
            }
        }

        if (activityMonitor.hasFirstActivity() && request.hasInactivityTimeout()) {
            long inactivityTimeoutMs = request.inactivityTimeoutUnit().toMillis(request.inactivityTimeout());
            if (now - activityMonitor.lastActivityMs() > inactivityTimeoutMs) {
                throw new PythonBridgeExecutionException(
                        "Timeout de inactividad en operación '" + request.operationName() + "' tras "
                                + request.inactivityTimeout() + " " + request.inactivityTimeoutUnit().name().toLowerCase());
            }
        }
    }

    private <T> void logAttemptFailure(PythonBridgeRequest<T> request, int attempt, Exception exception) {
        if (attempt < request.maxRetries()) {
            log.warn("Intento {} de '{}' falló: {}", attempt, request.operationName(), exception.getMessage());
        } else {
            log.error("Último intento de '{}' falló: {}", request.operationName(), exception.getMessage(), exception);
        }
    }

    private <T> String buildExitCodeMessage(
            PythonBridgeRequest<T> request,
            int exitCode,
            StderrCollector stderrCollector) {
        String stderrText = stderrCollector.text();
        if (!stderrText.isEmpty()) {
            return "Proceso Python de '" + request.operationName() + "' terminó con código " + exitCode
                    + ". stderr:\n" + stderrText;
        }
        return "Proceso Python de '" + request.operationName() + "' terminó con código " + exitCode;
    }

    @PreDestroy
    public void shutdown() {
        ioExecutor.shutdown();
        try {
            if (!ioExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                ioExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ioExecutor.shutdownNow();
        }
    }
}
