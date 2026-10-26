package com.bottrading.market.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.bottrading.market.application.port.out.VelaRepositoryPort;
import com.bottrading.shared.exceptions.DataFetchException;
import com.bottrading.trading.infrastructure.bridge.PythonBridgeFacade;
import com.sun.net.httpserver.HttpServer;

/**
 * Qué hace el relleno de huecos (fetchRange) cuando Binance falla: un servidor HTTP de mentira con respuestas
 * guiadas y el cliente HTTP de verdad. Espejo de tests/test_binance_faults.py del lado Python.
 */
@DisplayName("FetchService — fallos de Binance en fetchRange")
class FetchServiceBinanceFaultsTest {

    private static final long T0 = 1_700_000_000_000L - (1_700_000_000_000L % 3_600_000L);
    private static final long HORA = 3_600_000L;

    private record Reply(int status, String body, String retryAfter) {
        Reply(int status, String body) {
            this(status, body, null);
        }
    }

    private HttpServer server;
    private final List<Reply> script = new CopyOnWriteArrayList<>();
    private final List<String> queries = new CopyOnWriteArrayList<>();
    private JdbcTemplate jdbc;
    private FetchService service;
    private final List<Integer> filasPorLote = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v3/klines", exchange -> {
            queries.add(exchange.getRequestURI().getQuery());
            Reply reply = script.isEmpty() ? new Reply(200, "[]") : script.remove(0);
            byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
            if (reply.retryAfter() != null) {
                exchange.getResponseHeaders().add("Retry-After", reply.retryAfter());
            }
            exchange.sendResponseHeaders(reply.status(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        System.setProperty("cryptoapp.binance.klines-url",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v3/klines");

        jdbc = mock(JdbcTemplate.class);
        doAnswer(inv -> {
            filasPorLote.add(((List<?>) inv.getArgument(1)).size());
            return new int[0];
        }).when(jdbc).batchUpdate(anyString(), anyList());
        service = spy(new FetchService(mock(VelaRepositoryPort.class), jdbc, mock(PythonBridgeFacade.class)));
        doNothing().when(service).pausar(anyLong());
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("cryptoapp.binance.klines-url");
        server.stop(0);
    }

    private static String velas(int desde, int cuantas) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = desde; i < desde + cuantas; i++) {
            long t = T0 + i * HORA;
            sb.append(i > desde ? "," : "").append("[").append(t).append(",\"1\",\"2\",\"0.5\",\"1.5\",\"10\",")
                    .append(t + HORA - 1).append(",\"15\",7,\"5\",\"7\",\"0\"]");
        }
        return sb.append("]").toString();
    }

    private int rellenar(int horas) {
        LocalDateTime from = LocalDateTime.ofEpochSecond(T0 / 1000, 0, ZoneOffset.UTC);
        return service.fetchRange("BTCUSDT", "1h", from, from.plusHours(horas - 1));
    }

    @Test
    @DisplayName("Un 429 con Retry-After se reintenta esperando lo que pide Binance y acaba guardando las velas")
    void rate_limit_is_retried() {
        script.add(new Reply(429, "{\"code\":-1003,\"msg\":\"Too many requests\"}", "3"));
        script.add(new Reply(200, velas(0, 5)));

        assertEquals(5, rellenar(5));

        verify(service).pausar(3_000L);
        assertEquals(2, queries.size());
    }

    @Test
    @DisplayName("Un cuerpo que no es la lista de velas (HTML de un proxy, JSON cortado, objeto) se reintenta")
    void garbage_bodies_are_retried() {
        script.add(new Reply(200, "<html>Access denied</html>"));
        script.add(new Reply(200, "[[1,\"1\",\"2\""));
        script.add(new Reply(200, "{\"code\":-1,\"msg\":\"x\"}"));
        script.add(new Reply(200, velas(0, 3)));

        // tres cuerpos malos seguidos agotan los 3 intentos de la primera página
        DataFetchException e = assertThrows(DataFetchException.class, () -> rellenar(3));

        assertTrue(e.getMessage().contains("tras 3 intentos"), e.getMessage());
        assertTrue(e.getMessage().contains("0 velas guardadas"), e.getMessage());
    }

    @Test
    @DisplayName("Un cuerpo malo seguido de uno bueno se recupera sin perder la página")
    void one_bad_body_then_good() {
        script.add(new Reply(200, "<html>Bad gateway</html>"));
        script.add(new Reply(200, velas(0, 4)));

        assertEquals(4, rellenar(4));
        assertEquals(2, queries.size());
    }

    @Test
    @DisplayName("Un símbolo inválido (400) falla a la primera y el mensaje trae el code y el msg de Binance")
    void invalid_symbol_fails_fast_with_binance_message() {
        script.add(new Reply(400, "{\"code\":-1121,\"msg\":\"Invalid symbol.\"}"));

        DataFetchException e = assertThrows(DataFetchException.class, () -> rellenar(3));

        assertTrue(e.getMessage().contains("code=-1121: Invalid symbol."), e.getMessage());
        assertEquals(1, queries.size());
    }

    @Test
    @DisplayName("Un 503 persistente agota los reintentos y conserva lo ya descargado")
    void persistent_503_keeps_what_was_downloaded() {
        // dos páginas: la primera (1000 velas) llega, la segunda da 503 siempre
        script.add(new Reply(200, velas(0, 1000)));
        script.add(new Reply(503, ""));
        script.add(new Reply(503, ""));
        script.add(new Reply(503, ""));

        DataFetchException e = assertThrows(DataFetchException.class, () -> rellenar(1500));

        assertTrue(e.getMessage().contains("1000 velas guardadas"), e.getMessage());
        assertEquals(List.of(1000), filasPorLote);
        assertEquals(4, queries.size());
    }

    @Test
    @DisplayName("Una caída de red (conexión rechazada) se reintenta y acaba en DataFetchException, no en otra excepción")
    void network_down_is_retried_then_reported() {
        server.stop(0);

        DataFetchException e = assertThrows(DataFetchException.class, () -> rellenar(2));

        assertTrue(e.getMessage().contains("error de red"), e.getMessage());
        verify(service).pausar(1_000L);
        verify(service).pausar(2_000L);
    }

    @Test
    @DisplayName("Un Retry-After enorme (baneo de horas) no se espera: falla con su motivo")
    void huge_retry_after_is_not_awaited() {
        script.add(new Reply(418, "{\"code\":-1003,\"msg\":\"IP banned\"}", "7200"));

        DataFetchException e = assertThrows(DataFetchException.class, () -> rellenar(2));

        assertTrue(e.getMessage().contains("no se espera"), e.getMessage());
        assertTrue(e.getMessage().contains("IP banned"), e.getMessage());
    }

    @Test
    @DisplayName("Una fila de vela rota no escapa como excepción cruda y se conserva lo guardado")
    void malformed_candle_row_is_reported_cleanly() {
        script.add(new Reply(200, "[[" + T0 + ",\"1\"]]"));

        DataFetchException e = assertThrows(DataFetchException.class, () -> rellenar(2));

        assertTrue(e.getMessage().contains("formato inesperado"), e.getMessage());
    }

    @Test
    @DisplayName("Una interrupción del hilo no se confunde con un fallo de red")
    void interruption_is_not_retried() throws Exception {
        HttpClient client = mock(HttpClient.class);
        doAnswer(inv -> {
            throw new InterruptedException("parada");
        }).when(client).send(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        assertThrows(DataFetchException.class, () -> service.requestKlines(client, "BTCUSDT", "1h", T0, T0 + HORA));

        assertTrue(Thread.interrupted(), "debe dejar marcada la interrupción para quien llamó");
    }
}
