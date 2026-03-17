package com.bottrading.bridge.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IpcMessagePackCodecTest {

    @Test
    void writeAndReadEnvelopeShouldRoundTrip() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Map<String, Object> payload = Map.of("symbol", "BTCUSDT", "timeframe", "1h");

        IpcMessagePackCodec.writeEnvelope(out, IpcMessageType.FETCH_REQUEST, payload);

        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
        Map<String, Object> envelope = IpcMessagePackCodec.readEnvelope(in);

        assertEquals("1.0", envelope.get("protocol_version"));
        assertEquals("FETCH_REQUEST", envelope.get("message_type"));
        assertNotNull(envelope.get("correlation_id"));
        @SuppressWarnings("unchecked")
        Map<String, Object> decodedPayload = (Map<String, Object>) envelope.get("payload");
        assertEquals("BTCUSDT", decodedPayload.get("symbol"));
        assertEquals("1h", decodedPayload.get("timeframe"));
    }

    @Test
    void readEnvelopeShouldFailForIncompleteHeader() {
        ByteArrayInputStream in = new ByteArrayInputStream(new byte[] {0x00, 0x01});
        assertThrows(IOException.class, () -> IpcMessagePackCodec.readEnvelope(in));
    }
}
