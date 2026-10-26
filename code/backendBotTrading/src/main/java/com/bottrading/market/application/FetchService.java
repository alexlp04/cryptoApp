package com.bottrading.market.application;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Service;

import com.bottrading.market.application.port.in.FetchMarketDataUseCase;
import com.bottrading.market.application.port.out.VelaRepositoryPort;
import com.bottrading.market.domain.Timeframe;
import com.bottrading.market.domain.VelaDTO;
import com.bottrading.shared.exceptions.DataFetchException;
import com.bottrading.shared.utils.ConsoleLoader;
import com.bottrading.shared.utils.PathConfig;
import com.bottrading.trading.infrastructure.bridge.PythonBridgeExecutionException;
import com.bottrading.trading.infrastructure.bridge.PythonBridgeFacade;
import com.bottrading.trading.infrastructure.bridge.PythonBridgeRequest;
import com.bottrading.trading.infrastructure.bridge.protocol.IpcMessagePackCodec;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import lombok.extern.slf4j.Slf4j;

/**
 * Servicio encargado de la sincronización de datos de mercado
 * (Velas/Candlesticks)
 * entre un script externo de Python y la base de datos interna.
 * Gestiona descargas incrementales para optimizar el ancho de banda y el
 * almacenamiento.
 */
@Slf4j
@Service
public class FetchService implements FetchMarketDataUseCase {

    private static final TypeReference<List<VelaDTO>> VELA_DTO_LIST_TYPE = new TypeReference<>() {};
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final long PROGRESS_LOG_EVERY = 100_000L;

    private final VelaRepositoryPort velaRepo;
    private final JdbcTemplate jdbcTemplate;
    private final PythonBridgeFacade pythonBridgeFacade;

    // 10 000 filas por sentencia: un lote de 50 000 con 13 columnas tensaba memoria y max_allowed_packet de MySQL.
    private static final int BATCH_INSERT_SIZE = 10_000;
    private static final int MAX_RETRIES = 3;
        private static final int BINANCE_PAGE_LIMIT = 1000;
        private static final String BINANCE_KLINES_URL = "https://api.binance.com/api/v3/klines";
        /** Sobrescribible con -Dcryptoapp.binance.klines-url (un espejo, o el servidor falso de los tests). */
        private final String klinesUrl = System.getProperty("cryptoapp.binance.klines-url", BINANCE_KLINES_URL);
        private static final Duration BINANCE_HTTP_TIMEOUT = Duration.ofSeconds(30);
        private static final String SQL_UPSERT_VELA = """
            INSERT INTO vela (open_time, open, high, low, close, volume, close_time, quote_volume, trades, \
            taker_base_volume, taker_quote_volume, symbol, time_interval) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?) \
            ON DUPLICATE KEY UPDATE close = VALUES(close), volume = VALUES(volume)""";

            private record PageProcessResult(long lastOpenTime, int inserted) {
            }

        public FetchService(VelaRepositoryPort velaRepo,
            JdbcTemplate jdbcTemplate,
            PythonBridgeFacade pythonBridgeFacade) {
        this.velaRepo = velaRepo;
        this.jdbcTemplate = jdbcTemplate;
        this.pythonBridgeFacade = pythonBridgeFacade;
    }

    /**
     * Verifica el historial almacenado y lanza UNA SOLA descarga Python si faltan datos.
     *
     * Si existen huecos: llama a Python desde el primer punto faltante hasta "now".
     *   → Python descarga en paralelo (5 workers). Los datos ya presentes entre huecos
     *     se re-insertan por ON DUPLICATE KEY UPDATE (no-op, son velas cerradas inmutables).
     * Si no hay huecos: solo descarga la cola nueva desde lastTimestamp+1.
     */
    @Override
    public void fetch(String symbol, String interval) {
        log.info("Comprobando datos para: {} [{}]", symbol, interval);
        Long lastTimestamp;
        Long minTimestamp = null;
        ConsoleLoader.getInstance().startDots("Verificando historial de " + symbol + " [" + interval + "]");
        try {
            lastTimestamp = velaRepo.findMaxOpenTimeBySymbolAndInterval(symbol, interval);
            if (lastTimestamp != null) {
                minTimestamp = velaRepo.findMinOpenTimeBySymbolAndInterval(symbol, interval);
            }
        } finally {
            ConsoleLoader.getInstance().stopClear(); // también si el repositorio lanza: el spinner no queda girando
        }

        if (lastTimestamp == null) {
            log.info("No hay datos previos. Iniciando descarga completa...");
            callPythonAndSave(symbol, interval, null);
            return;
        }

        long intervalMillis = getIntervalMillis(interval);
        long now = System.currentTimeMillis();

        if (minTimestamp != null) {
            Long firstMissing = findFirstMissingPoint(symbol, interval, minTimestamp, lastTimestamp, intervalMillis);
            if (firstMissing != null) {
                log.info("Hueco detectado para {} [{}] desde {}. Rellenando con descarga paralela.",
                        symbol, interval, firstMissing);
                callPythonAndSave(symbol, interval, firstMissing);
                return;
            }
        }

        long fetchFrom = lastTimestamp + intervalMillis;
        if (fetchFrom > now) {
            ConsoleLoader.getInstance().stop("✅ Historial de " + symbol + " ya está actualizado.");
            log.info("Los datos ya están al día.");
        } else {
            log.info("Actualizando cola para {} [{}] desde: {}", symbol, interval, fetchFrom);
            callPythonAndSave(symbol, interval, fetchFrom);
        }
    }

    /**
     * Modo full refresh: elimina histórico del símbolo/intervalo y vuelve a descargar
     * desde el inicio o desde una ventana de días.
     */
    @Override
    public void fullRefresh(String symbol, String interval, Integer days) {
        Long fromTimestamp = null;
        if (days != null) {
            long millisPerDay = 24L * 60L * 60L * 1000L;
            fromTimestamp = System.currentTimeMillis() - days * millisPerDay;
        }

        Long deletionAnchor = Optional.ofNullable(fromTimestamp)
                .orElseGet(() -> velaRepo.findMinOpenTimeBySymbolAndInterval(symbol, interval));

        if (deletionAnchor != null) {
            velaRepo.deleteBySymbolAndIntervalAndOpenTimeGreaterThanEqual(symbol, interval, deletionAnchor);
        }

        callPythonAndSave(symbol, interval, fromTimestamp);
    }

    @Override
    public LocalDateTime findOldestTimestamp(String symbol, String interval) {
        Long oldest = velaRepo.findMinOpenTimeBySymbolAndInterval(symbol, interval);
        if (oldest == null) {
            return null;
        }
        return LocalDateTime.ofEpochSecond(oldest / 1000L, (int) ((oldest % 1000L) * 1_000_000), ZoneOffset.UTC);
    }

    /**
     * Modo gap fill: activa descarga desde el inicio de un hueco detectado.
     * Nota: el downloader actual opera desde fromTimestamp hasta "now".
     */
    @Override
    public void fillGapRange(String symbol, String interval, long fromTimestamp, long toTimestamp) {
        LocalDateTime from = LocalDateTime.ofEpochSecond(
                fromTimestamp / 1000L,
                (int) ((fromTimestamp % 1000L) * 1_000_000),
                ZoneOffset.UTC);
        LocalDateTime to = LocalDateTime.ofEpochSecond(
                toTimestamp / 1000L,
                (int) ((toTimestamp % 1000L) * 1_000_000),
                ZoneOffset.UTC);
        fetchRange(symbol, interval, from, to);
    }

    /**
     * Descarga velas en el rango exacto [from, to] y las inserta en BD con INSERT plano.
     */
    @Override
    public int fetchRange(String symbol, String timeframe, LocalDateTime from, LocalDateTime to) {
        validateFetchRangeInputs(symbol, timeframe, from, to);

        if (to.isBefore(from)) {
            return 0;
        }

        long intervalMs = getIntervalMillis(timeframe);
        long fromMs = from.toInstant(ZoneOffset.UTC).toEpochMilli();
        long toMs = to.toInstant(ZoneOffset.UTC).toEpochMilli();

        HttpClient client = HttpClient.newHttpClient();
        List<Object[]> insertBatch = new ArrayList<>(BATCH_INSERT_SIZE);
        int totalInserted = 0;

        long cursor = fromMs;
        try {
            while (cursor <= toMs) {
                long pageEnd = Math.min(toMs, cursor + (BINANCE_PAGE_LIMIT - 1L) * intervalMs);

                JsonArray klines = requestKlines(client, symbol, timeframe, cursor, pageEnd);
                long next = pageEnd + intervalMs; // página vacía: hueco real de Binance, se sigue con la siguiente
                if (!klines.isEmpty()) {
                    PageProcessResult pageResult = processPage(klines, symbol, timeframe, fromMs, toMs, insertBatch);
                    totalInserted += pageResult.inserted();
                    if (pageResult.lastOpenTime() >= cursor) {
                        next = pageResult.lastOpenTime() + intervalMs;
                    }
                }
                cursor = next;
            }
        } catch (DataFetchException e) {
            // Lo ya descargado no se pierde: se guarda y el error dice hasta dónde se llegó.
            if (!insertBatch.isEmpty()) {
                totalInserted += insertarLote(insertBatch);
            }
            throw new DataFetchException("Relleno de [" + symbol + "][" + timeframe + "] interrumpido en "
                    + Instant.ofEpochMilli(cursor) + " (" + totalInserted + " velas guardadas): " + e.getMessage(), e);
        }

        if (!insertBatch.isEmpty()) {
            totalInserted += insertarLote(insertBatch);
            insertBatch.clear();
        }

        log.info("Filled gap [{}][{}]: {} -> {} ({} candles inserted)", symbol, timeframe, from, to, totalInserted);
        return totalInserted;
    }

    private void validateFetchRangeInputs(String symbol, String timeframe, LocalDateTime from, LocalDateTime to) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol no puede ser nulo o vacío");
        }
        if (timeframe == null || timeframe.isBlank()) {
            throw new IllegalArgumentException("timeframe no puede ser nulo o vacío");
        }
        if (from == null || to == null) {
            throw new IllegalArgumentException("from/to no pueden ser nulos");
        }
    }

    private PageProcessResult processPage(
            JsonArray klines,
            String symbol,
            String timeframe,
            long fromMs,
            long toMs,
            List<Object[]> insertBatch) {
        long lastOpenTimeInPage = -1L;
        int inserted = 0;

        for (JsonElement element : klines) {
            long openTime;
            Object[] args;
            try {
                JsonArray candle = element.getAsJsonArray();
                openTime = candle.get(0).getAsLong();
                args = openTime >= fromMs && openTime <= toMs ? mapCandleToInsertArgs(candle, symbol, timeframe) : null;
            } catch (IllegalStateException | IndexOutOfBoundsException | NumberFormatException
                    | UnsupportedOperationException e) {
                throw new DataFetchException("Vela con formato inesperado en la respuesta de Binance: "
                        + recortar(element.toString()), e);
            }
            if (args != null) {
                lastOpenTimeInPage = openTime;
                insertBatch.add(args);

                if (insertBatch.size() >= BATCH_INSERT_SIZE) {
                    inserted += insertarLote(insertBatch);
                    insertBatch.clear();
                }
            }
        }

        return new PageProcessResult(lastOpenTimeInPage, inserted);
    }

    /**
     * Descarga incremental para una ventana de N días.
     *
     * Caso 1: Sin datos o historial anterior a la ventana → descarga completa desde windowStart.
     * Caso 2: Hay huecos → una sola llamada Python desde el primer punto faltante hasta "now".
     *   Los datos ya presentes entre huecos se re-insertan con ON DUPLICATE KEY (no-op).
     * Caso 3: Sin huecos → solo descarga cola nueva desde lastTimestamp+1.
     *
     * La detección de huecos usa findFirstInternalGapOpenTime (continuidad SQL),
     * sin comparación de conteos que genera falsos positivos con timestamps no alineados.
     */
    @Override
    public long fetchIncremental(String symbol, String interval, int dias, long now) {
        long millisPerDay = 24L * 60L * 60L * 1000L;
        long windowStart = now - (long) dias * millisPerDay;
        long intervalMillis = getIntervalMillis(interval);

        ConsoleLoader.getInstance().startDots("Analizando datos para " + symbol + " [" + interval + "]");
        Long lastTimestamp;
        try {
            lastTimestamp = velaRepo.findMaxOpenTimeBySymbolAndInterval(symbol, interval);
        } finally {
            ConsoleLoader.getInstance().stopClear();
        }

        // Caso 1: Sin datos o historial insuficiente — descarga desde el inicio de la ventana
        if (lastTimestamp == null || lastTimestamp < windowStart) {
            long fetchFrom = (lastTimestamp != null) ? lastTimestamp + intervalMillis : windowStart;
            log.info("Sin historial suficiente para ventana de {} días. Descargando desde: {}", dias, fetchFrom);
            callPythonAndSave(symbol, interval, fetchFrom);
            return fetchFrom;
        }

        // Caso 2: Detectar primer hueco — una sola llamada Python cubre huecos + cola
        Long firstMissing = findFirstMissingPoint(symbol, interval, windowStart, lastTimestamp, intervalMillis);
        if (firstMissing != null) {
            log.info("Huecos detectados para {} [{}] desde {}. Descarga paralela hasta now (ON DUPLICATE KEY).",
                    symbol, interval, firstMissing);
            callPythonAndSave(symbol, interval, firstMissing);
            return firstMissing;
        }

        // Caso 3: Sin huecos — solo descargar cola nueva
        long fetchFrom = lastTimestamp + intervalMillis;
        if (fetchFrom > now) {
            log.info("Historial completo y al día para {} [{}].", symbol, interval);
            return lastTimestamp;
        }

        log.info("Sin huecos. Descargando cola nueva para {} [{}] desde: {}", symbol, interval, fetchFrom);
        callPythonAndSave(symbol, interval, fetchFrom);
        return fetchFrom;
    }

    /**
     * Devuelve el primer timestamp faltante en [windowStart, lastTimestamp],
     * o null si toda la ventana tiene datos continuos.
     *
     * Tolerancia: si el primer dato existente está a ≤ 1 intervalo de windowStart,
     * se trata como desfase de alineación normal, no como hueco real.
     */
    private Long findFirstMissingPoint(String symbol, String interval,
            long windowStart, long lastTimestamp, long intervalMillis) {
        Long firstExisting = velaRepo.findMinOpenTimeBySymbolAndIntervalAndOpenTimeBetween(
                symbol, interval, windowStart, lastTimestamp);

        if (firstExisting == null) {
            return windowStart;
        }

        // Un hueco ya pedido con éxito a Binance en esta ejecución es un hueco real del exchange (par
        // más joven que la ventana, mantenimientos): volver a pedirlo no lo rellena y repetía la
        // descarga completa en cada llamada.
        if (firstExisting > windowStart + intervalMillis && !yaCubierto(symbol, interval, windowStart)) {
            return windowStart;
        }

        Long gap = velaRepo.findFirstInternalGapOpenTime(
                symbol, interval, firstExisting, lastTimestamp, intervalMillis);
        return gap != null && yaCubierto(symbol, interval, gap) ? null : gap;
    }

    /**
     * Menor instante desde el que se ha descargado con éxito por (símbolo, timeframe). Vive en la
     * tabla cobertura_velas; el mapa es una caché para no consultarla en cada llamada.
     */
    private final Map<String, Long> cubiertoDesde = new ConcurrentHashMap<>();

    private static String clave(String symbol, String interval) {
        return symbol + "|" + interval;
    }

    private boolean yaCubierto(String symbol, String interval, long desde) {
        Long cubierto = cubiertoDesde.get(clave(symbol, interval));
        if (cubierto == null) {
            cubierto = jdbcTemplate.query(
                    "SELECT cubierto_desde FROM cobertura_velas WHERE symbol = ? AND time_interval = ?",
                    (ResultSetExtractor<Long>) rs -> rs.next() ? rs.getLong(1) : null, symbol, interval);
            if (cubierto != null) {
                cubiertoDesde.put(clave(symbol, interval), cubierto);
            }
        }
        return cubierto != null && desde >= cubierto;
    }

    private void marcarCubierto(String symbol, String interval, long desde) {
        cubiertoDesde.merge(clave(symbol, interval), desde, Math::min);
        try {
            jdbcTemplate.update(
                    "INSERT INTO cobertura_velas (symbol, time_interval, cubierto_desde) VALUES (?, ?, ?) "
                            + "ON DUPLICATE KEY UPDATE cubierto_desde = LEAST(cubierto_desde, VALUES(cubierto_desde))",
                    symbol, interval, desde);
        } catch (DataAccessException e) {
            // La caché ya lo recuerda en esta ejecución; solo se pierde la memoria entre ejecuciones.
            log.warn("No se pudo guardar la cobertura de {} [{}]: {}", symbol, interval, e.getMessage());
        }
    }

    /**
     * Ejecuta el script extractor de Python y persiste los datos usando MessagePack streaming.
     * Implementa timeouts, retries automáticos y manejo de errores robusto.
     */
    private void callPythonAndSave(String symbol, String interval, Long fromTimestamp) {
        try {
            ejecutarIntentoFetch(symbol, interval, fromTimestamp);
            // null = descarga completa desde el listado
            marcarCubierto(symbol, interval, fromTimestamp == null ? 0L : fromTimestamp);
        } catch (Exception e) {
            throw new DataFetchException("Data synchronization failed", e);
        }
    }

    private void ejecutarIntentoFetch(String symbol, String interval, Long fromTimestamp)
            throws PythonBridgeExecutionException {
        ConsoleLoader.getInstance().startSpinner("Descargando velas de Binance: " + symbol + " [" + interval + "]");
        try {
            List<String> args = new ArrayList<>();
            args.add(symbol);
            args.add(interval);
            if (fromTimestamp != null) {
                args.add(String.valueOf(fromTimestamp));
            }

            PythonBridgeRequest<Integer> request = PythonBridgeRequest.<Integer>builder(PathConfig.FETCHER_PATH)
                    .operationName("fetch-" + symbol)
                    .args(args)
                    .noTimeout()
                    .maxRetries(MAX_RETRIES)
                    .retryDelayMs(1000L)
                    .stdoutReader(in -> leerVelasEnStreamingYGuardar(in, symbol, interval))
                    .onStderrLine(line -> log.info("PY [fetch:{}]: {}", symbol, line))
                    .build();

            int totalGuardadas = pythonBridgeFacade.execute(request);

            ConsoleLoader.getInstance().stop("✅ " + symbol + " [" + interval + "] — " + totalGuardadas + " velas sincronizadas");
            log.info("Fetch completado para {}. Total guardado: {}", symbol, totalGuardadas);
        } catch (PythonBridgeExecutionException e) {
            ConsoleLoader.getInstance().stopClear();
            String rootCause = e.getCause() != null ? e.getCause().getMessage() : "sin causa interna";
            log.error("Error crítico en FetchService para {}: {} | causa raíz: {}", symbol, e.getMessage(), rootCause,
                    e);
            throw e;
        }
    }

    /**
     * Lee velas desde Python en streaming IPC (frames MessagePack) e inserta en BD por lotes.
     * Si Python cierra con un frame FETCH_RESPONSE, su total debe coincidir con las velas recibidas.
     */
    private int leerVelasEnStreamingYGuardar(InputStream in, String symbol, String interval) throws IOException {
        int totalGuardadas = 0;
        long tiempoInicio = System.currentTimeMillis();
        List<Object[]> batch = new ArrayList<>(BATCH_INSERT_SIZE);
        long siguienteHito = PROGRESS_LOG_EVERY;

        Optional<Map<String, Object>> maybeEnvelope;
        while ((maybeEnvelope = IpcMessagePackCodec.readEnvelopeOrEmpty(in)).isPresent()) {
            Map<String, Object> envelope = maybeEnvelope.get();
            lanzarSiEsError(envelope);
            if ("FETCH_RESPONSE".equals(envelope.get("message_type"))) {
                comprobarTotal(envelope, totalGuardadas + batch.size());
                continue;
            }
            for (VelaDTO dto : extraerVelasChunk(envelope)) {
                batch.add(aArgumentosInsert(dto, symbol, interval));
                if (batch.size() >= BATCH_INSERT_SIZE) {
                    totalGuardadas += insertarLote(batch);
                    batch.clear();
                }
            }
            ConsoleLoader.getInstance().updateMessage(
                    "Descargando " + symbol + " [" + interval + "] — " + (totalGuardadas + batch.size()) + " velas");
            if (totalGuardadas >= siguienteHito) {
                long segundos = Math.max(1L, (System.currentTimeMillis() - tiempoInicio) / 1000L);
                log.info("Progreso: {} velas guardadas ({} velas/seg)", totalGuardadas, totalGuardadas / segundos);
                siguienteHito = totalGuardadas + PROGRESS_LOG_EVERY;
            }
        }

        if (!batch.isEmpty()) {
            totalGuardadas += insertarLote(batch);
            batch.clear();
        }

        long tiempoTotal = System.currentTimeMillis() - tiempoInicio;
        double velocidadMedia = totalGuardadas > 0 ? totalGuardadas / (Math.max(1L, tiempoTotal) / 1000.0) : 0;
        log.info("Lectura MessagePack completada: {} registros guardados en {} ms ({} registros/seg)",
                totalGuardadas, tiempoTotal, String.format("%.0f", velocidadMedia));

        return totalGuardadas;
    }

    /** El total que anuncia Python debe ser el de las velas recibidas: si no, se perdió algún frame. */
    static void comprobarTotal(Map<String, Object> envelope, int recibidas) {
        Object payload = envelope.get("payload");
        Object total = payload instanceof Map<?, ?> m ? m.get("total") : null;
        if (total instanceof Number n && n.intValue() != recibidas) {
            throw new DataFetchException("Python anunció " + n.intValue() + " velas pero llegaron " + recibidas);
        }
    }

    private static Object[] aArgumentosInsert(VelaDTO dto, String symbol, String interval) {
        return new Object[] {
            dto.getOpenTime(),
            new BigDecimal(dto.getOpen()),
            new BigDecimal(dto.getHigh()),
            new BigDecimal(dto.getLow()),
            new BigDecimal(dto.getClose()),
            new BigDecimal(dto.getVolume()),
            dto.getCloseTime(),
            new BigDecimal(dto.getQuoteVolume()),
            dto.getTrades(),
            new BigDecimal(dto.getTakerBaseVolume()),
            new BigDecimal(dto.getTakerQuoteVolume()),
            symbol,
            interval
        };
    }

    /** Un frame ERROR de Python es un fallo de la descarga, no "0 velas". */
    static void lanzarSiEsError(Map<String, Object> envelope) {
        if ("ERROR".equals(envelope.get("message_type"))) {
            Object payload = envelope.get("payload");
            Object message = payload instanceof Map<?, ?> m ? m.get("message") : null;
            throw new DataFetchException("Fetch fallido en Python: " + message);
        }
    }

    private List<VelaDTO> extraerVelasChunk(Map<String, Object> envelope) {
        Object payloadObj = envelope.get("payload");
        if (!(payloadObj instanceof Map<?, ?> payloadMap)) {
            return List.of();
        }

        Object velasObj = payloadMap.get("velas");
        if (velasObj == null) {
            return List.of();
        }

        // Directo del Map de MessagePack a los DTO, sin pasar por un String JSON por cada frame de 2 000 velas.
        return MAPPER.convertValue(velasObj, VELA_DTO_LIST_TYPE);
    }

    private int insertarLote(@org.springframework.lang.NonNull List<Object[]> batch) {
        try {
            jdbcTemplate.batchUpdate(SQL_UPSERT_VELA, batch);
            // Con ON DUPLICATE KEY MySQL devuelve 1/2/0 según insertó, actualizó o no cambió nada: no vale como
            // recuento. Cuenta las filas enviadas, que es lo que el log y el spinner dicen ("velas sincronizadas").
            return batch.size();
        } catch (Exception e) {
            log.error("Error inserting batch of {} records: {}", batch.size(), e.getMessage());
            throw new DataFetchException("Batch insert failed", e);
        }
    }

    private Object[] mapCandleToInsertArgs(JsonArray candle, String symbol, String timeframe) {
        return new Object[] {
            candle.get(0).getAsLong(),
            new BigDecimal(candle.get(1).getAsString()),
            new BigDecimal(candle.get(2).getAsString()),
            new BigDecimal(candle.get(3).getAsString()),
            new BigDecimal(candle.get(4).getAsString()),
            new BigDecimal(candle.get(5).getAsString()),
            candle.get(6).getAsLong(),
            new BigDecimal(candle.get(7).getAsString()),
            candle.get(8).getAsInt(),
            new BigDecimal(candle.get(9).getAsString()),
            new BigDecimal(candle.get(10).getAsString()),
            symbol,
            timeframe
        };
    }

    /** Tope de espera entre reintentos de una página: un 418 (baneo) puede pedir horas y no se espera. */
    private static final long MAX_ESPERA_REINTENTO_MS = 60_000L;

    /**
     * Una página de velas. Se reintenta (MAX_RETRIES, 1 s, 2 s, 4 s o el Retry-After de Binance) lo que puede
     * arreglarse solo: red caída o timeout, 429/418/5xx y un cuerpo que no es la lista de velas (HTML de un
     * proxy, JSON cortado). Un 4xx (símbolo inválido, parámetros) falla a la primera, con el code/msg de Binance.
     */
    JsonArray requestKlines(HttpClient client,
            String symbol,
            String timeframe,
            long startTime,
            long endTime) {
        URI uri = buildKlinesUri(symbol, timeframe, startTime, endTime);
        HttpRequest request = HttpRequest.newBuilder(uri).GET().timeout(BINANCE_HTTP_TIMEOUT).build();
        for (int intento = 1; ; intento++) {
            long espera = 1000L << (intento - 1);
            String fallo;
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status == 200) {
                    JsonArray klines = comoLista(response.body());
                    if (klines != null) {
                        return klines;
                    }
                    fallo = "respuesta que no es una lista de velas: " + recortar(response.body());
                } else if (status == 429 || status == 418 || status >= 500) {
                    espera = esperaReintentoMs(response, intento);
                    if (espera > MAX_ESPERA_REINTENTO_MS) {
                        throw new DataFetchException("Binance returned HTTP " + status + " con Retry-After de "
                                + espera / 1000 + " s: no se espera" + detalleBinance(response.body()));
                    }
                    fallo = "HTTP " + status;
                } else {
                    throw new DataFetchException("Binance returned HTTP " + status + detalleBinance(response.body()));
                }
            } catch (IOException e) {
                // Un timeout o un corte de red no es una interrupción: marcarla rompía las operaciones bloqueantes siguientes.
                fallo = "error de red: " + e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new DataFetchException("Interrupted requesting klines for range", e);
            }
            if (intento >= MAX_RETRIES) {
                throw new DataFetchException("Binance sigue fallando tras " + intento + " intentos (" + fallo + ")");
            }
            log.warn("Binance para {} [{}]: {}; reintento {}/{} en {} ms",
                    symbol, timeframe, fallo, intento, MAX_RETRIES, espera);
            pausar(espera);
        }
    }

    /** El cuerpo como lista JSON, o null si no lo es. */
    private static JsonArray comoLista(String body) {
        try {
            JsonElement parsed = JsonParser.parseString(body);
            return parsed.isJsonArray() ? parsed.getAsJsonArray() : null;
        } catch (JsonParseException e) {
            return null;
        }
    }

    private static String recortar(String body) {
        String limpio = body == null ? "" : body.strip().replaceAll("\\s+", " ");
        return limpio.length() > 100 ? limpio.substring(0, 100) + "..." : limpio;
    }

    /** « (code=-1121: Invalid symbol.)» si el cuerpo es el error JSON de Binance; vacío si no. */
    private static String detalleBinance(String body) {
        try {
            JsonObject error = JsonParser.parseString(body).getAsJsonObject();
            if (error.has("code") && error.has("msg")) {
                return " (code=" + error.get("code").getAsString() + ": " + error.get("msg").getAsString() + ")";
            }
        } catch (JsonParseException | IllegalStateException | NullPointerException e) {
            log.debug("Cuerpo de error sin formato de Binance: {}", recortar(body));
        }
        return "";
    }

    /** Retry-After en segundos (si es una fecha HTTP no se interpreta); sin él, 1 s, 2 s, 4 s... */
    private static long esperaReintentoMs(HttpResponse<String> response, int intento) {
        return response.headers().firstValue("Retry-After")
                .map(String::trim)
                .filter(v -> v.chars().allMatch(Character::isDigit) && !v.isEmpty())
                .map(v -> Long.parseLong(v) * 1000L)
                .orElse(1000L << (intento - 1));
    }

    void pausar(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DataFetchException("Interrupted waiting to retry klines request", e);
        }
    }

    private URI buildKlinesUri(String symbol, String timeframe, long startTime, long endTime) {
        String query = "symbol=" + URLEncoder.encode(symbol, StandardCharsets.UTF_8)
                + "&interval=" + URLEncoder.encode(timeframe, StandardCharsets.UTF_8)
                + "&startTime=" + startTime
                + "&endTime=" + endTime
                + "&limit=" + BINANCE_PAGE_LIMIT;
        return URI.create(klinesUrl + "?" + query);
    }

    /** Un timeframe no soportado falla: antes caía a 1 min y la detección de huecos re-descargaba todo cada vez. */
    public static long getIntervalMillis(String interval) {
        return Timeframe.parse(interval).millis();
    }
}