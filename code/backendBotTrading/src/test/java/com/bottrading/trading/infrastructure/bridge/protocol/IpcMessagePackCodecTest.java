package com.bottrading.trading.infrastructure.bridge.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** El framing de 4 bytes + MessagePack es el contrato más caro de depurar si se rompe (CLAUDE.md, «Contrato IPC»). */
class IpcMessagePackCodecTest {

    private static byte[] escribir(IpcMessageType tipo, Object payload) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        IpcMessagePackCodec.writeEnvelope(out, tipo, payload);
        return out.toByteArray();
    }

    @Test
    @DisplayName("El sobre lleva exactamente las cuatro claves del contrato y el payload vuelve intacto")
    void round_trip() throws IOException {
        byte[] bytes = escribir(IpcMessageType.FETCH_CHUNK, Map.of("total", 3, "texto", "ñ"));

        Map<String, Object> envelope = IpcMessagePackCodec.readEnvelope(new ByteArrayInputStream(bytes));

        assertEquals(java.util.Set.of("protocol_version", "message_type", "correlation_id", "payload"), envelope.keySet());
        assertEquals("FETCH_CHUNK", envelope.get("message_type"));
        assertEquals(IpcProtocol.PROTOCOL_VERSION, envelope.get("protocol_version"));
        assertEquals(Map.of("total", 3, "texto", "ñ"), envelope.get("payload"));
    }

    @Test
    @DisplayName("La cabecera es la longitud del cuerpo en 4 bytes big-endian")
    void header_is_big_endian_length() throws IOException {
        byte[] bytes = escribir(IpcMessageType.ERROR, null);

        assertEquals(bytes.length - 4, ByteBuffer.wrap(bytes, 0, 4).getInt());
        assertEquals(Map.of(), IpcMessagePackCodec.readEnvelope(new ByteArrayInputStream(bytes)).get("payload"));
    }

    @Test
    @DisplayName("Varios frames seguidos se leen uno a uno y al final del flujo no hay más")
    void reads_consecutive_frames_until_eof() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        IpcMessagePackCodec.writeEnvelope(out, IpcMessageType.FETCH_CHUNK, Map.of("n", 1));
        IpcMessagePackCodec.writeEnvelope(out, IpcMessageType.FETCH_RESPONSE, Map.of("n", 2));
        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());

        assertEquals("FETCH_CHUNK", IpcMessagePackCodec.readEnvelopeOrEmpty(in).orElseThrow().get("message_type"));
        assertEquals("FETCH_RESPONSE", IpcMessagePackCodec.readEnvelopeOrEmpty(in).orElseThrow().get("message_type"));
        assertTrue(IpcMessagePackCodec.readEnvelopeOrEmpty(in).isEmpty());
    }

    @Test
    @DisplayName("Una longitud declarada mayor que el cuerpo recibido es un frame truncado, no un cuelgue")
    void truncated_body_fails() throws IOException {
        byte[] bytes = escribir(IpcMessageType.FETCH_CHUNK, Map.of("n", 1));
        byte[] cortado = java.util.Arrays.copyOf(bytes, bytes.length - 3);

        IOException e = assertThrows(IOException.class,
                () -> IpcMessagePackCodec.readEnvelope(new ByteArrayInputStream(cortado)));

        assertTrue(e.getMessage().contains("truncado"));
    }

    @Test
    @DisplayName("Una cabecera incompleta o una longitud no positiva se rechazan")
    void bad_headers_fail() {
        assertThrows(IOException.class,
                () -> IpcMessagePackCodec.readEnvelopeOrEmpty(new ByteArrayInputStream(new byte[] {0, 0})));
        assertThrows(IOException.class,
                () -> IpcMessagePackCodec.readEnvelopeOrEmpty(new ByteArrayInputStream(new byte[] {0, 0, 0, 0})));
        assertThrows(IOException.class,
                () -> IpcMessagePackCodec.readEnvelopeOrEmpty(new ByteArrayInputStream(new byte[] {-1, -1, -1, -1})));
    }

    @Test
    @DisplayName("readEnvelope sobre un flujo vacío lanza; readEnvelopeOrEmpty devuelve vacío")
    void empty_stream() throws IOException {
        assertTrue(IpcMessagePackCodec.readEnvelopeOrEmpty(new ByteArrayInputStream(new byte[0])).isEmpty());
        assertThrows(IOException.class, () -> IpcMessagePackCodec.readEnvelope(new ByteArrayInputStream(new byte[0])));
    }
}
