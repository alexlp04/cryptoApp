package com.bottrading.trading.infrastructure.bridge.protocol;

import java.util.UUID;

/**
 * Utilidades comunes de protocolo IPC.
 */
public final class IpcProtocol {

    public static final String PROTOCOL_VERSION = "1.0";

    private IpcProtocol() {
    }

    public static String newCorrelationId() {
        return UUID.randomUUID().toString();
    }

    public static <T> IpcEnvelope<T> envelope(IpcMessageType messageType, T payload) {
        return new IpcEnvelope<>(
                PROTOCOL_VERSION,
                messageType.name(),
                newCorrelationId(),
                payload);
    }
}
