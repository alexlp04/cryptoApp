package com.bottrading.infrastructure.bridge.protocol;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import org.msgpack.jackson.dataformat.MessagePackFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Codec IPC basado en MessagePack con framing length-prefixed (4 bytes big-endian).
 */
public final class IpcMessagePackCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper(new MessagePackFactory());
    private static final int HEADER_BYTES = 4;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private IpcMessagePackCodec() {
    }

    public static void writeEnvelope(OutputStream outputStream, IpcMessageType messageType, Object payload) throws IOException {
        Map<String, Object> envelope = Map.of(
                "protocol_version", IpcProtocol.PROTOCOL_VERSION,
                "message_type", messageType.name(),
                "correlation_id", IpcProtocol.newCorrelationId(),
                "payload", payload == null ? Map.of() : payload);

        byte[] body = MAPPER.writeValueAsBytes(envelope);
        outputStream.write(ByteBuffer.allocate(HEADER_BYTES).putInt(body.length).array());
        outputStream.write(body);
        outputStream.flush();
    }

    public static Map<String, Object> readEnvelope(InputStream inputStream) throws IOException {
        return readEnvelopeOrEmpty(inputStream)
                .orElseThrow(() -> new IOException("No se pudo leer header de framing MessagePack"));
    }

    public static Optional<Map<String, Object>> readEnvelopeOrEmpty(InputStream inputStream) throws IOException {
        int firstByte = inputStream.read();
        if (firstByte == -1) {
            return Optional.empty();
        }

        byte[] remainingHeader = inputStream.readNBytes(HEADER_BYTES - 1);
        if (remainingHeader.length < HEADER_BYTES - 1) {
            throw new IOException("No se pudo leer header completo de framing MessagePack");
        }

        byte[] header = new byte[HEADER_BYTES];
        header[0] = (byte) firstByte;
        System.arraycopy(remainingHeader, 0, header, 1, HEADER_BYTES - 1);

        int length = ByteBuffer.wrap(header).getInt();
        if (length <= 0) {
            throw new IOException("Frame MessagePack con longitud inválida: " + length);
        }

        byte[] body = inputStream.readNBytes(length);
        if (body.length < length) {
            throw new IOException("Frame truncado: esperados " + length + " bytes, recibidos " + body.length);
        }

        return Optional.of(MAPPER.readValue(body, MAP_TYPE));
    }

    public static String readUtf8Fallback(InputStream inputStream) throws IOException {
        return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
    }
}
