package com.bottrading.market.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.bottrading.market.application.port.out.VelaRepositoryPort;
import com.bottrading.shared.exceptions.DataFetchException;
import com.bottrading.trading.infrastructure.bridge.PythonBridgeFacade;
import com.bottrading.trading.infrastructure.bridge.PythonBridgeRequest;
import com.bottrading.trading.infrastructure.bridge.protocol.IpcMessagePackCodec;
import com.bottrading.trading.infrastructure.bridge.protocol.IpcMessageType;

/** La lectura de los frames de Python: lo que llega por la tubería acaba, entero, en la tabla vela. */
@DisplayName("FetchService — lectura en streaming de los frames de Python")
class FetchServiceStreamingTest {

    private VelaRepositoryPort velaRepo;
    private JdbcTemplate jdbc;
    private PythonBridgeFacade bridge;
    private FetchService service;
    private final List<Integer> tamañosDeLote = new ArrayList<>();
    private Object[] primeraFila;

    @BeforeEach
    void setUp() {
        velaRepo = mock(VelaRepositoryPort.class);
        jdbc = mock(JdbcTemplate.class);
        bridge = mock(PythonBridgeFacade.class);
        service = new FetchService(velaRepo, jdbc, bridge);
    }

    private static Map<String, Object> vela(long openTime) {
        // Python manda también claves que el DTO no conoce (p. ej. un campo nuevo): no deben romper la lectura
        Map<String, Object> v = new java.util.LinkedHashMap<>();
        v.put("openTime", openTime);
        v.put("open", "1.5");
        v.put("high", "2");
        v.put("low", "1");
        v.put("close", "1.8");
        v.put("volume", "10");
        v.put("closeTime", openTime + 59_999);
        v.put("quoteVolume", "18");
        v.put("trades", 7);
        v.put("takerBaseVolume", "5");
        v.put("takerQuoteVolume", "9");
        v.put("campoNuevo", "x");
        return v;
    }

    private static byte[] frames(Object... tipoYPayload) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < tipoYPayload.length; i += 2) {
            IpcMessagePackCodec.writeEnvelope(out, (IpcMessageType) tipoYPayload[i], tipoYPayload[i + 1]);
        }
        return out.toByteArray();
    }

    /** Hace que el puente "ejecute" el script entregando estos bytes al lector de stdout de la petición. */
    @SuppressWarnings("unchecked")
    private void python(byte[] stdout) throws Exception {
        doAnswer(inv -> ((PythonBridgeRequest<Integer>) inv.getArgument(0)).stdoutReader()
                .apply(new ByteArrayInputStream(stdout))).when(bridge).execute(any());
    }

    private int fetchCompleto() {
        service.fetch("BTCUSDT", "1m");
        return 0;
    }

    @Test
    @DisplayName("Los chunks se insertan por lotes y la cuenta es la de velas enviadas")
    void insertaTodasLasVelas() throws Exception {
        List<Map<String, Object>> primero = new ArrayList<>(), segundo = new ArrayList<>();
        for (long i = 0; i < 3; i++) {
            primero.add(vela(i * 60_000));
            segundo.add(vela((i + 3) * 60_000));
        }
        // el lote se vacía tras insertar: se anota su contenido en el momento de la llamada
        doAnswer(inv -> {
            List<Object[]> lote = inv.getArgument(1);
            tamañosDeLote.add(lote.size());
            primeraFila = lote.get(0);
            return new int[lote.size()];
        }).when(jdbc).batchUpdate(anyString(), anyList());
        python(frames(IpcMessageType.FETCH_CHUNK, Map.of("velas", primero),
                IpcMessageType.FETCH_CHUNK, Map.of("velas", segundo),
                IpcMessageType.FETCH_RESPONSE, Map.of("total", 6)));

        fetchCompleto();

        verify(jdbc, times(1)).batchUpdate(anyString(), anyList());
        assertEquals(List.of(6), tamañosDeLote);
        assertEquals(new java.math.BigDecimal("1.5"), primeraFila[1]);
    }

    @Test
    @DisplayName("Si el total anunciado no coincide con lo recibido, falla en vez de dar la descarga por buena")
    void totalQueNoCoincideFalla() throws Exception {
        python(frames(IpcMessageType.FETCH_CHUNK, Map.of("velas", List.of(vela(0))),
                IpcMessageType.FETCH_RESPONSE, Map.of("total", 5)));

        DataFetchException e = assertThrows(DataFetchException.class, this::fetchCompleto);

        assertEquals(true, e.getCause().getMessage().contains("anunció 5 velas pero llegaron 1")
                || e.getMessage().contains("anunció 5 velas pero llegaron 1"));
    }

    @Test
    @DisplayName("Un frame ERROR de Python aborta con su mensaje")
    void frameDeErrorAborta() throws Exception {
        python(frames(IpcMessageType.ERROR, Map.of("message", "símbolo inválido")));

        assertThrows(DataFetchException.class, this::fetchCompleto);
        verify(jdbc, times(0)).batchUpdate(anyString(), anyList());
    }
}
