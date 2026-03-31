package com.bottrading.infrastructure.bridge.protocol;

/**
 * Contrato IPC versionado entre Java y Python.
 *
 * Campos mínimos acordados:
 * - protocolVersion
 * - messageType
 * - correlationId
 * - payload
 */
public record IpcEnvelope<T>(
        String protocolVersion,
        String messageType,
        String correlationId,
        T payload) {
}
