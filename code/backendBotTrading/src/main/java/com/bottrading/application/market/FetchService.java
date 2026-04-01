package com.bottrading.application.market;

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

import com.bottrading.domain.market.IndicadorRepository;
import com.bottrading.domain.market.VelaDTO;
import com.bottrading.domain.market.VelaRepository;
import com.bottrading.exceptions.DataFetchException;
import com.bottrading.infrastructure.bridge.PythonBridgeExecutionException;
import com.bottrading.infrastructure.bridge.PythonBridgeFacade;
import com.bottrading.infrastructure.bridge.PythonBridgeRequest;
import com.bottrading.infrastructure.bridge.protocol.IpcMessagePackCodec;
import com.bottrading.utils.ConsoleLoader;
import com.bottrading.utils.PathConfig;
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
public class FetchService {

    private static final Type VELA_DTO_LIST_TYPE = new TypeToken<List<VelaDTO>>() {}.getType();

    private final Gson gson = new Gson();

    private final VelaRepository velaRepo;
    private final IndicadorRepository indicadorRepo;
    private final JdbcTemplate jdbcTemplate;
    private final PythonBridgeFacade pythonBridgeFacade;

    private static final int BATCH_INSERT_SIZE = 50000;
    private static final int MAX_RETRIES = 3;
        private static final int BINANCE_PAGE_LIMIT = 1000;
        private static final String BINANCE_KLINES_URL = "https://api.binance.com/api/v3/klines";
        private static final Duration BINANCE_HTTP_TIMEOUT = Duration.ofSeconds(30);
        private static final String SQL_INSERT_VELA_PLAIN =
            "INSERT INTO vela (open_time, open, high, low, close, volume, close_time, quote_volume, trades, "
                + "taker_base_volume, taker_quote_volume, symbol, time_interval) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)";

            private record PageProcessResult(long lastOpenTime, int inserted) {
            }

    public FetchService(VelaRepository velaRepo,
            IndicadorRepository indicadorRepo,
            JdbcTemplate jdbcTemplate,
            PythonBridgeFacade pythonBridgeFacade) {
        this.velaRepo = velaRepo;
        this.indicadorRepo = indicadorRepo;
        this.jdbcTemplate = jdbcTemplate;
        this.pythonBridgeFacade = pythonBridgeFacade;
    }

    /**
     * Coordina la descarga incremental de datos de mercado.
     * Incluye detección de huecos en el rango histórico completo para evitar
     * falsos "al día" cuando hay agujeros antiguos.
     */
    public void fetch(String symbol, String interval) {
        log.info("Comprobando datos para: {} [{}]", symbol, interval);
        ConsoleLoader.getInstance().startDots("Verificando historial local para " + symbol);

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
        Long firstGap = null;
        if (minTimestamp != null) {
            firstGap = encontrarPrimerHueco(symbol, interval, minTimestamp, lastTimestamp, intervalMillis);
        }

        ConsoleLoader.getInstance().stopClear();

        if (firstGap != null) {
            log.warn("Hueco histórico detectado para {} [{}]. Resincronizando desde {}", symbol, interval, firstGap);
            callPythonAndSave(symbol, interval, firstGap);
        } else if (now - lastTimestamp > intervalMillis) {
            log.info("Datos desactualizados. Descargando desde: {}", lastTimestamp);
            callPythonAndSave(symbol, interval, lastTimestamp + 1);
        } else {
            ConsoleLoader.getInstance().stop("✅ Historial de " + symbol + " ya está actualizado.");
            log.info("Los datos ya están al día.");
        }
    }

    /**
     * Modo full refresh: elimina histórico del símbolo/intervalo y vuelve a descargar
     * desde el inicio o desde una ventana de días.
     */
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

    /**
     * Modo gap fill: activa descarga desde el inicio de un hueco detectado.
     * Nota: el downloader actual opera desde fromTimestamp hasta "now".
     */
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
     * Coordina la descarga incremental inteligente.
     */
    public long fetchIncremental(String symbol, String interval, int dias, long now) {
        long millisPerDay = 24L * 60L * 60L * 1000L;
        long targetTimestamp = now - dias * millisPerDay;

        ConsoleLoader.getInstance().startDots("Analizando brechas de datos para " + symbol);
        Long lastTimestamp = velaRepo.findMaxOpenTimeBySymbolAndInterval(symbol, interval);
        long intervalMillis = getIntervalMillis(interval);
        long fetchFromTimestamp;

        if (lastTimestamp != null && lastTimestamp > targetTimestamp) {
            Long firstGap = encontrarPrimerHueco(symbol, interval, targetTimestamp, lastTimestamp, intervalMillis);
            if (firstGap != null) {
                fetchFromTimestamp = firstGap;
                log.warn("Hueco detectado en ventana de entrenamiento ({} días). Resincronizando desde {}", dias,
                        fetchFromTimestamp);
            } else {
                fetchFromTimestamp = lastTimestamp;
                log.info("Historial detectado sin huecos. Descargando solo nuevas velas desde: {}", lastTimestamp);
            }
        } else {
            fetchFromTimestamp = targetTimestamp;
            log.info("Historial incompleto. Descargando {} días completos desde: {}", dias, targetTimestamp);
        }

        ConsoleLoader.getInstance().stopClear();
        ConsoleLoader.getInstance().startSpinner("Limpiando datos residuales de " + symbol);
        
        indicadorRepo.deleteByVelaSymbolAndIntervalAndOpenTimeGreaterThanEqual(symbol, interval, fetchFromTimestamp);
        velaRepo.deleteBySymbolAndIntervalAndOpenTimeGreaterThanEqual(symbol, interval, fetchFromTimestamp);

        ConsoleLoader.getInstance().stopClear();
        
        callPythonAndSave(symbol, interval, fetchFromTimestamp);

        return fetchFromTimestamp;
    }

    /**
     * Encuentra el primer hueco de velas en [fromTimestamp, toTimestamp].
     * Si no hay huecos, devuelve null.
     */
    private Long encontrarPrimerHueco(String symbol, String interval, long fromTimestamp, long toTimestamp,
            long intervalMillis) {
        if (toTimestamp < fromTimestamp) {
            return null;
        }

        long expected = ((toTimestamp - fromTimestamp) / intervalMillis) + 1;
        long actual = velaRepo.countBySymbolAndIntervalAndOpenTimeBetween(symbol, interval, fromTimestamp, toTimestamp);

        if (actual >= expected) {
            return null;
        }

        Long firstInRange = velaRepo.findMinOpenTimeBySymbolAndIntervalAndOpenTimeBetween(symbol, interval,
                fromTimestamp, toTimestamp);
        if (firstInRange == null) {
            return fromTimestamp;
        }

        if (firstInRange > fromTimestamp) {
            return fromTimestamp;
        }

        Long internalGap = velaRepo.findFirstInternalGapOpenTime(symbol, interval, fromTimestamp, toTimestamp,
                intervalMillis);
        if (internalGap != null) {
            return internalGap;
        }

        Long maxInRange = velaRepo.findMaxOpenTimeBySymbolAndIntervalAndOpenTimeBetween(symbol, interval,
                fromTimestamp, toTimestamp);
        if (maxInRange != null && maxInRange + intervalMillis <= toTimestamp) {
            return maxInRange + intervalMillis;
        }

        // Fallback defensivo si el conteo detectó inconsistencia pero no se pudo localizar.
        return fromTimestamp;
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
        ConsoleLoader.getInstance().startSpinner("Sincronizando velas con MessagePack para " + symbol);
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

            ConsoleLoader.getInstance().stop("✅ Sincronización completa para " + symbol + ". Velas: " + totalGuardadas);
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

        Map<String, Object> envelope;
        while (!(envelope = IpcMessagePackCodec.readEnvelopeOrNull(in)).isEmpty()) {
            List<VelaDTO> velasChunk = extraerVelasChunk(envelope);
            if (!velasChunk.isEmpty()) {
                totalGuardadas += procesarChunkVelas(velasChunk, symbol, interval, batch, totalGuardadas, tiempoInicio);
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
        
        String sql = "INSERT INTO vela (open_time, open, high, low, close, volume, close_time, quote_volume, trades, taker_base_volume, taker_quote_volume, symbol, time_interval) " +
                     "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?) " +
                     "ON DUPLICATE KEY UPDATE " +
                     "  close = VALUES(close), volume = VALUES(volume)";

        try {
            int[] resultados = jdbcTemplate.batchUpdate(sql, batch);
            int insertadas = (int) Arrays.stream(resultados).filter(r -> r > 0).count();
            log.debug("Batch inserted: {} records", batch.size());
            return insertadas;
        } catch (Exception e) {
            log.error("Error inserting batch of {} records: {}", batch.size(), e.getMessage());
            throw new DataFetchException("Batch insert failed", e);
        }
    }

    private int executePlainInsertBatch(@org.springframework.lang.NonNull List<Object[]> batch) {
        try {
            int[] results = jdbcTemplate.batchUpdate(SQL_INSERT_VELA_PLAIN, batch);
            return (int) Arrays.stream(results).filter(r -> r > 0).count();
        } catch (Exception e) {
            throw new DataFetchException("Plain batch insert failed", e);
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
        return switch (interval) {
            case "1m" -> 60_000L;
            case "5m" -> 300_000L;
            case "15m" -> 900_000L;
            case "1h" -> 3_600_000L;
            case "4h" -> 14_400_000L;
            case "1d" -> 86_400_000L;
            default -> throw new IllegalArgumentException("Timeframe no soportado para fetchRange: " + interval);
        };
    }

    public static long getIntervalMillis(String interval) {
        return switch (interval) {
            case "1m" -> 60_000L;
            case "5m" -> 300_000L;
            case "15m" -> 900_000L;
            case "1h" -> 3_600_000L;
            case "4h" -> 14_400_000L;
            case "1d" -> 86_400_000L;
            default -> 60_000L;
        };
    }

    @PreDestroy
    public void shutdown() {
        log.info("FetchService shutdown completo");
    }
}