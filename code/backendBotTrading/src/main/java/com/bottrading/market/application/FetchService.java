package com.bottrading.market.application;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.bottrading.market.application.port.in.FetchMarketDataUseCase;
import com.bottrading.market.application.port.out.IndicadorRepositoryPort;
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
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import jakarta.annotation.PreDestroy;
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

    private static final Type VELA_DTO_LIST_TYPE = new TypeToken<List<VelaDTO>>() {}.getType();

    private final Gson gson = new Gson();

    private final VelaRepositoryPort velaRepo;
    private final IndicadorRepositoryPort indicadorRepo;
    private final JdbcTemplate jdbcTemplate;
    private final PythonBridgeFacade pythonBridgeFacade;

    private static final int BATCH_INSERT_SIZE = 50000;
    private static final int MAX_RETRIES = 3;
        private static final int BINANCE_PAGE_LIMIT = 1000;
        private static final String BINANCE_KLINES_URL = "https://api.binance.com/api/v3/uiKlines";
        private static final Duration BINANCE_HTTP_TIMEOUT = Duration.ofSeconds(30);
        private static final String SQL_INSERT_VELA_PLAIN = """
            INSERT INTO vela (open_time, open, high, low, close, volume, close_time, quote_volume, trades, \
            taker_base_volume, taker_quote_volume, symbol, time_interval) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?) \
            ON DUPLICATE KEY UPDATE close = VALUES(close), volume = VALUES(volume)""";

            private record PageProcessResult(long lastOpenTime, int inserted) {
            }

        public FetchService(VelaRepositoryPort velaRepo,
            IndicadorRepositoryPort indicadorRepo,
            JdbcTemplate jdbcTemplate,
            PythonBridgeFacade pythonBridgeFacade) {
        this.velaRepo = velaRepo;
        this.indicadorRepo = indicadorRepo;
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
        ConsoleLoader.getInstance().startDots("Verificando historial de " + symbol + " [" + interval + "]");

        Long lastTimestamp = velaRepo.findMaxOpenTimeBySymbolAndInterval(symbol, interval);
        if (lastTimestamp == null) {
            ConsoleLoader.getInstance().stopClear();
            log.info("No hay datos previos. Iniciando descarga completa...");
            callPythonAndSave(symbol, interval, null);
            return;
        }

        long intervalMillis = getIntervalMillis(interval);
        long now = System.currentTimeMillis();
        Long minTimestamp = velaRepo.findMinOpenTimeBySymbolAndInterval(symbol, interval);
        ConsoleLoader.getInstance().stopClear();

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
            indicadorRepo.deleteByVelaSymbolAndIntervalAndOpenTimeGreaterThanEqual(symbol, interval, deletionAnchor);
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

        long intervalMs = resolveIntervalMillisStrict(timeframe);
        long fromMs = from.toInstant(ZoneOffset.UTC).toEpochMilli();
        long toMs = to.toInstant(ZoneOffset.UTC).toEpochMilli();

        HttpClient client = HttpClient.newHttpClient();
        List<Object[]> insertBatch = new ArrayList<>(BATCH_INSERT_SIZE);
        int totalInserted = 0;

        long cursor = fromMs;
        boolean hasMore = true;
        while (cursor <= toMs && hasMore) {
            long pageEnd = Math.min(toMs, cursor + (BINANCE_PAGE_LIMIT - 1L) * intervalMs);

            JsonArray klines = requestKlines(client, symbol, timeframe, cursor, pageEnd);
            hasMore = !klines.isEmpty();
            if (hasMore) {
                PageProcessResult pageResult = processPage(klines, symbol, timeframe, fromMs, toMs, insertBatch);
                totalInserted += pageResult.inserted();

                hasMore = pageResult.lastOpenTime() >= cursor;
                if (hasMore) {
                    cursor = pageResult.lastOpenTime() + intervalMs;
                }
            }
        }

        if (!insertBatch.isEmpty()) {
            totalInserted += executePlainInsertBatch(insertBatch);
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
            JsonArray candle = element.getAsJsonArray();
            long openTime = candle.get(0).getAsLong();
            if (openTime >= fromMs && openTime <= toMs) {
                lastOpenTimeInPage = openTime;
                insertBatch.add(mapCandleToInsertArgs(candle, symbol, timeframe));

                if (insertBatch.size() >= BATCH_INSERT_SIZE) {
                    inserted += executePlainInsertBatch(insertBatch);
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
        Long lastTimestamp = velaRepo.findMaxOpenTimeBySymbolAndInterval(symbol, interval);
        ConsoleLoader.getInstance().stopClear();

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

        if (firstExisting > windowStart + intervalMillis) {
            return windowStart;
        }

        return velaRepo.findFirstInternalGapOpenTime(
                symbol, interval, firstExisting, lastTimestamp, intervalMillis);
    }

    /**
     * Ejecuta el script extractor de Python y persiste los datos usando MessagePack streaming.
     * Implementa timeouts, retries automáticos y manejo de errores robusto.
     */
    private void callPythonAndSave(String symbol, String interval, Long fromTimestamp) {
        try {
            ejecutarIntentoFetch(symbol, interval, fromTimestamp);
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
     */
    private int leerVelasEnStreamingYGuardar(InputStream in, String symbol, String interval) throws IOException {
        int totalGuardadas = 0;
        long tiempoInicio = System.currentTimeMillis();
        List<Object[]> batch = new ArrayList<>(BATCH_INSERT_SIZE);

        Optional<Map<String, Object>> maybeEnvelope;
        while ((maybeEnvelope = IpcMessagePackCodec.readEnvelopeOrEmpty(in)).isPresent()) {
            Map<String, Object> envelope = maybeEnvelope.get();
            List<VelaDTO> velasChunk = extraerVelasChunk(envelope);
            if (!velasChunk.isEmpty()) {
                totalGuardadas += procesarChunkVelas(velasChunk, symbol, interval, batch, totalGuardadas, tiempoInicio);
                ConsoleLoader.getInstance().updateMessage(
                        "Descargando " + symbol + " [" + interval + "] — " + totalGuardadas + " velas");
            }
        }

        if (!batch.isEmpty()) {
            int insertadas = guardarBatchVelas(batch);
            totalGuardadas += insertadas;
            batch.clear();
        }

        long tiempoTotal = System.currentTimeMillis() - tiempoInicio;
        double velocidadMedia = totalGuardadas > 0 ? totalGuardadas / (tiempoTotal / 1000.0) : 0;
        log.info("Lectura MessagePack completada: {} registros guardados en {} ms ({} registros/seg)",
                totalGuardadas, tiempoTotal, String.format("%.0f", velocidadMedia));

        return totalGuardadas;
    }

    private int procesarChunkVelas(List<VelaDTO> velasChunk,
            String symbol,
            String interval,
            List<Object[]> batch,
            int totalPrevio,
            long tiempoInicio) {
        int insertadasEnChunk = 0;
        for (VelaDTO dto : velasChunk) {
            Object[] datos = new Object[] {
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

            batch.add(datos);

            if (batch.size() >= BATCH_INSERT_SIZE) {
                int insertadas = guardarBatchVelas(batch);
                insertadasEnChunk += insertadas;
                int totalActual = totalPrevio + insertadasEnChunk;
                if (totalActual % 100000 == 0) {
                    long tiempoTranscurrido = System.currentTimeMillis() - tiempoInicio;
                    double velocidad = totalActual / (tiempoTranscurrido / 1000.0);
                    log.info("Progreso: {} registros guardados ({} registros/seg)",
                            totalActual, String.format("%.0f", velocidad));
                }
                batch.clear();
            }
        }
        return insertadasEnChunk;
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

        String velasJson = gson.toJson(velasObj);
        List<VelaDTO> velas = gson.fromJson(velasJson, VELA_DTO_LIST_TYPE);
        return velas != null ? velas : List.of();
    }

    /**
     * Inserta batch de velas directamente en BD usando JDBC sin ORM.
     * ON DUPLICATE KEY UPDATE maneja solapamiento automáticamente.
     */
    private int guardarBatchVelas(List<Object[]> batch) {
        if (batch.isEmpty()) {
            return 0;
        }
        int insertadas = executePlainInsertBatch(batch);
        log.debug("Batch inserted: {} records", batch.size());
        return insertadas;
    }

    private int executePlainInsertBatch(@org.springframework.lang.NonNull List<Object[]> batch) {
        try {
            int[] results = jdbcTemplate.batchUpdate(SQL_INSERT_VELA_PLAIN, batch);
            return (int) Arrays.stream(results).filter(r -> r > 0).count();
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

    private JsonArray requestKlines(HttpClient client,
            String symbol,
            String timeframe,
            long startTime,
            long endTime) {
        try {
            URI uri = buildKlinesUri(symbol, timeframe, startTime, endTime);
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .GET()
                    .timeout(BINANCE_HTTP_TIMEOUT)
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new DataFetchException("Binance returned HTTP " + response.statusCode());
            }

            return JsonParser.parseString(response.body()).getAsJsonArray();
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DataFetchException("Error requesting klines for range", e);
        }
    }

    private URI buildKlinesUri(String symbol, String timeframe, long startTime, long endTime) {
        String query = "symbol=" + URLEncoder.encode(symbol, StandardCharsets.UTF_8)
                + "&interval=" + URLEncoder.encode(timeframe, StandardCharsets.UTF_8)
                + "&startTime=" + startTime
                + "&endTime=" + endTime
                + "&limit=" + BINANCE_PAGE_LIMIT;
        return URI.create(BINANCE_KLINES_URL + "?" + query);
    }

    private long resolveIntervalMillisStrict(String interval) {
        return Timeframe.buscar(interval)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Timeframe no soportado para fetchRange: " + interval))
                .millis();
    }

    public static long getIntervalMillis(String interval) {
        return Timeframe.millisOrDefault(interval, Timeframe.M1.millis());
    }

    @PreDestroy
    public void shutdown() {
        log.info("FetchService shutdown completo");
    }
}