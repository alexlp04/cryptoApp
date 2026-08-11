package com.bottrading.trading.infrastructure.bridge;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Configuración de una ejecución de proceso Python vía {@link PythonBridgeFacade}.
 */
public record PythonBridgeRequest<T>(
        String operationName,
        String scriptPath,
        boolean redirectErrorStream,
        List<String> args,
        long timeout,
        TimeUnit timeoutUnit,
        long startupTimeout,
        TimeUnit startupTimeoutUnit,
        long inactivityTimeout,
        TimeUnit inactivityTimeoutUnit,
        int maxRetries,
        long retryDelayMs,
        boolean failOnNonZeroExit,
        ThrowingOutputStreamConsumer stdinWriter,
        ThrowingInputStreamFunction<T> stdoutReader,
        Consumer<String> onStderrLine) {

    @FunctionalInterface
    public interface ThrowingOutputStreamConsumer {
        void accept(OutputStream outputStream) throws Exception;
    }

    @FunctionalInterface
    public interface ThrowingInputStreamFunction<T> {
        T apply(InputStream inputStream) throws Exception;
    }

    public PythonBridgeRequest {
        args = List.copyOf(args);
    }

    public static <T> Builder<T> builder(String scriptPath) {
        return new Builder<>(scriptPath);
    }

    public boolean hasTimeout() {
        return timeout > 0;
    }

    public boolean hasStartupTimeout() {
        return startupTimeout > 0;
    }

    public boolean hasInactivityTimeout() {
        return inactivityTimeout > 0;
    }

    public static final class Builder<T> {
        private final String scriptPath;
        private String operationName = "python-call";
        private boolean redirectErrorStream = false;
        private List<String> args = new ArrayList<>();
        private long timeout = 30L;
        private TimeUnit timeoutUnit = TimeUnit.SECONDS;
        private long startupTimeout = 0L;
        private TimeUnit startupTimeoutUnit = TimeUnit.SECONDS;
        private long inactivityTimeout = 0L;
        private TimeUnit inactivityTimeoutUnit = TimeUnit.SECONDS;
        private int maxRetries = 1;
        private long retryDelayMs = 0L;
        private boolean failOnNonZeroExit = true;
        private ThrowingOutputStreamConsumer stdinWriter;
        private ThrowingInputStreamFunction<T> stdoutReader;
        private Consumer<String> onStderrLine;

        private Builder(String scriptPath) {
            this.scriptPath = scriptPath;
        }

        public Builder<T> operationName(String operationName) {
            this.operationName = operationName;
            return this;
        }

        public Builder<T> redirectErrorStream(boolean redirectErrorStream) {
            this.redirectErrorStream = redirectErrorStream;
            return this;
        }

        public Builder<T> args(List<String> args) {
            this.args = new ArrayList<>(args);
            return this;
        }

        public Builder<T> timeout(long timeout, TimeUnit timeoutUnit) {
            this.timeout = timeout;
            this.timeoutUnit = timeoutUnit;
            return this;
        }

        public Builder<T> noTimeout() {
            this.timeout = 0L;
            this.timeoutUnit = TimeUnit.SECONDS;
            return this;
        }

        public Builder<T> startupTimeout(long startupTimeout, TimeUnit startupTimeoutUnit) {
            this.startupTimeout = startupTimeout;
            this.startupTimeoutUnit = startupTimeoutUnit;
            return this;
        }

        public Builder<T> inactivityTimeout(long inactivityTimeout, TimeUnit inactivityTimeoutUnit) {
            this.inactivityTimeout = inactivityTimeout;
            this.inactivityTimeoutUnit = inactivityTimeoutUnit;
            return this;
        }

        public Builder<T> maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        public Builder<T> retryDelayMs(long retryDelayMs) {
            this.retryDelayMs = retryDelayMs;
            return this;
        }

        public Builder<T> failOnNonZeroExit(boolean failOnNonZeroExit) {
            this.failOnNonZeroExit = failOnNonZeroExit;
            return this;
        }

        public Builder<T> stdinWriter(ThrowingOutputStreamConsumer stdinWriter) {
            this.stdinWriter = stdinWriter;
            return this;
        }

        public Builder<T> stdoutReader(ThrowingInputStreamFunction<T> stdoutReader) {
            this.stdoutReader = stdoutReader;
            return this;
        }

        public Builder<T> onStderrLine(Consumer<String> onStderrLine) {
            this.onStderrLine = onStderrLine;
            return this;
        }

        public PythonBridgeRequest<T> build() {
            if (scriptPath == null || scriptPath.isBlank()) {
                throw new IllegalArgumentException("scriptPath no puede ser nulo o vacío");
            }
            if (timeout < 0) {
                throw new IllegalArgumentException("timeout debe ser >= 0");
            }
            if (startupTimeout < 0) {
                throw new IllegalArgumentException("startupTimeout debe ser >= 0");
            }
            if (inactivityTimeout < 0) {
                throw new IllegalArgumentException("inactivityTimeout debe ser >= 0");
            }
            if (maxRetries < 1) {
                throw new IllegalArgumentException("maxRetries debe ser >= 1");
            }
            return new PythonBridgeRequest<>(
                    operationName, scriptPath, redirectErrorStream, args,
                    timeout, timeoutUnit, startupTimeout, startupTimeoutUnit,
                    inactivityTimeout, inactivityTimeoutUnit, maxRetries, retryDelayMs,
                    failOnNonZeroExit, stdinWriter, stdoutReader, onStderrLine);
        }
    }
}
