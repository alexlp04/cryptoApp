package com.bottrading.application.training;

import com.bottrading.application.market.MarketDataService;
import com.bottrading.infrastructure.bridge.PythonBridgeExecutionException;
import com.bottrading.infrastructure.bridge.PythonBridgeFacade;
import com.bottrading.infrastructure.bridge.PythonBridgeRequest;
import com.bottrading.infrastructure.bridge.protocol.IpcMessagePackCodec;
import com.bottrading.infrastructure.bridge.protocol.IpcMessageType;
import com.bottrading.domain.market.IndicadorTecnico;
import com.bottrading.domain.market.Vela;
import com.bottrading.config.ProcessExecutorConfig;
import com.bottrading.exceptions.StrategyExecutionException;
import com.bottrading.domain.market.IndicadorRepository;
import com.bottrading.domain.market.VelaRepository;
import com.bottrading.utils.PathConfig;
import com.bottrading.utils.StrategyInspector;
import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Servicio encargado de orquestar el entrenamiento de modelos de Inteligencia
 * Artificial.
 * Fusiona los datos de mercado (Velas) con el Feature Engineering (Indicadores
 * Técnicos) y delega el entrenamiento al script de Python.
 *
 * Mejoras en Fase 3:
 * - Timeouts de 30s y retries automáticos (hasta 3 intentos)
 * - ExecutorService para gestión de procesos
 * - Destrucción forzada de procesos en caso de timeout
 * - Constructor injection (no @Autowired)
 */
@Slf4j
@Service
public class AITrainingService {

    private final Gson gson = new Gson();

    private final MarketDataService marketDataService;
    private final VelaRepository velaRepo;
    private final IndicadorRepository indicadorRepo;
    private final PythonBridgeFacade pythonBridgeFacade;

    public AITrainingService(MarketDataService marketDataService, VelaRepository velaRepo,
            IndicadorRepository indicadorRepo, PythonBridgeFacade pythonBridgeFacade) {
        this.marketDataService = marketDataService;
        this.velaRepo = velaRepo;
        this.indicadorRepo = indicadorRepo;
        this.pythonBridgeFacade = pythonBridgeFacade;
    }

    /**
     * Orquesta el proceso de preparación de datos y entrenamiento con retries automáticos.
     */
    public String entrenarModelo(String nombreModelo, String timeframe, String symbol, int dias,
            Map<String, Object> hyperparams, String strategyName) {
        return ejecutarEntrenamiento(nombreModelo, timeframe, symbol, dias, hyperparams, strategyName);
    }

    private String ejecutarEntrenamiento(String nombreModelo, String timeframe, String symbol, int dias,
            Map<String, Object> hyperparams, String strategyName) throws StrategyExecutionException {
        try {
            long now = System.currentTimeMillis();
            final boolean useDynamicStrategy = strategyName != null && !strategyName.isBlank();

            Integer warmupCandles = null;
            Integer totalCandles = null;
            int daysForPreparation = dias;
            if (useDynamicStrategy) {
                warmupCandles = StrategyInspector.getWarmupPeriod(strategyName);
                totalCandles = StrategyInspector.getCandlesRequired(strategyName, timeframe, dias);
                int candlesPerDay = resolveCandlesPerDay(timeframe);
                daysForPreparation = (int) Math.ceil((double) totalCandles / candlesPerDay);
            }

            marketDataService.prepararDatosParaEntrenamiento(symbol, timeframe, daysForPreparation, now);

            log.info("Extrayendo dataset {} de la base de datos...",
                    useDynamicStrategy ? "(Velas OHLCV para estrategia dinámica)" : "(Velas + Indicadores)");

            // Calcular timestamp objetivo
            long targetTimestamp;
            if (useDynamicStrategy) {
                targetTimestamp = now - (daysForPreparation * 24L * 60L * 60L * 1000L);
                log.info("Modo estrategia dinámica: strategy='{}', warmup={}, velas_requeridas={}, dias_efectivos={}",
                        strategyName, warmupCandles, totalCandles, daysForPreparation);
            } else {
                targetTimestamp = now - (dias * 24L * 60L * 60L * 1000L);
            }

            List<Vela> velas = velaRepo.findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(
                    symbol, timeframe, targetTimestamp);

            if (velas.isEmpty()) {
                return "Error: No hay datos suficientes de " + symbol + " para entrenar.";
            }

            List<Map<String, Object>> dataset = new ArrayList<>();
            List<IndicadorTecnico> todosLosIndicadores = new ArrayList<>();
            Map<Long, List<IndicadorTecnico>> indicadoresPorVela = new HashMap<>();

            if (useDynamicStrategy) {
                log.info("Extraídas {} velas. Construyendo dataset OHLCV puro para Python...", velas.size());
                for (Vela v : velas) {
                    Map<String, Object> row = new HashMap<>();
                    row.put("timestamp", v.getOpenTime());
                    row.put("open", v.getOpen());
                    row.put("high", v.getHigh());
                    row.put("low", v.getLow());
                    row.put("close", v.getClose());
                    row.put("volume", v.getVolume());
                    dataset.add(row);
                }
            } else {
                log.info("Extraídas {} velas. Obteniendo indicadores en bloque (Alta velocidad)...", velas.size());
                todosLosIndicadores = indicadorRepo.findByVelaIn(velas);
                indicadoresPorVela = todosLosIndicadores.stream()
                        .collect(Collectors.groupingBy(ind -> ind.getVela().getId()));

                log.info("Fusionando datos en memoria RAM...");
                for (Vela v : velas) {
                    Map<String, Object> row = new HashMap<>();
                    row.put("timestamp", v.getOpenTime());
                    row.put("close", v.getClose());
                    row.put("volume", v.getVolume());

                    List<IndicadorTecnico> indicadoresVela = indicadoresPorVela.getOrDefault(v.getId(), new ArrayList<>());
                    for (IndicadorTecnico ind : indicadoresVela) {
                        row.put(ind.getTipo(), ind.getValor());
                    }

                    dataset.add(row);
                }
            }

            log.info("--- DATASET LISTO --- {} registros", dataset.size());

            Map<String, Object> payload = new HashMap<>();
            payload.put("model_type", nombreModelo);
            payload.put("symbol", symbol);
            payload.put("timeframe", timeframe);
            payload.put("dataset", dataset);
            payload.put("hyperparameters", hyperparams);
            if (useDynamicStrategy) {
                payload.put("strategy_name", strategyName);
                payload.put("warmup_candles", warmupCandles);
            }

            String jsonPayload = gson.toJson(payload);

            log.info("Enviando {} registros al motor de IA (Python)...", dataset.size());

            // Limpieza de memoria RAM
            velas.clear();
            todosLosIndicadores.clear();
            indicadoresPorVela.clear();
            dataset.clear();

            return invocarMotorPythonConTimeouts(jsonPayload);

        } catch (Exception e) {
            log.error("Fallo durante entrenamiento: {}", e.getMessage(), e);
            throw new StrategyExecutionException("Error crítico entrenando modelo: " + e.getMessage(), e);
        }
    }

    /**
     * Invoca el motor Python con timeout de 30s y destrucción forzada en caso de error.
     */
    private String invocarMotorPythonConTimeouts(String jsonPayload) throws StrategyExecutionException {
        try {
            long startTime = System.currentTimeMillis();
            log.debug("Iniciando entrenamiento IA con payload de {} bytes", jsonPayload.length());

            PythonBridgeRequest<String> request = PythonBridgeRequest.<String>builder(PathConfig.ENGINE_TRAIN_PATH)
                    .operationName("train-model")
                    .noTimeout()
                    .maxRetries(ProcessExecutorConfig.MAX_RETRIES)
                    .retryDelayMs(ProcessExecutorConfig.RETRY_DELAY_MS)
                    .stdinWriter(os -> escribirEnvelopeTrain(os, jsonPayload))
                    .stdoutReader(this::parseResponseWithFallback)
                    .onStderrLine(line -> log.info("PY [train]: {}", line))
                    .build();

            String stdout = pythonBridgeFacade.execute(request);

            long duration = System.currentTimeMillis() - startTime;
            log.info("Entrenamiento IA completado en {} ms", duration);

            return stdout;
        } catch (PythonBridgeExecutionException e) {
            throw new StrategyExecutionException("Entrenamiento IA falló: " + e.getMessage(), e);
        } catch (StrategyExecutionException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error inesperado en entrenamiento IA: {}", e.getMessage(), e);
            throw new StrategyExecutionException("Error inesperado: " + e.getMessage(), e);
        }
    }

    private void escribirEnvelopeTrain(java.io.OutputStream outputStream, String jsonPayload) throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = gson.fromJson(jsonPayload, Map.class);
        IpcMessagePackCodec.writeEnvelope(outputStream, IpcMessageType.TRAIN_REQUEST, payload);
    }

    private String leerEnvelopeTrain(InputStream inputStream) throws IOException {
        Map<String, Object> envelope = IpcMessagePackCodec.readEnvelope(inputStream);
        Object payload = envelope.get("payload");
        return gson.toJson(payload == null ? Map.of() : payload);
    }

    private String parseResponseWithFallback(InputStream inputStream) throws IOException {
        byte[] raw = inputStream.readAllBytes();
        try {
            return leerEnvelopeTrain(new java.io.ByteArrayInputStream(raw));
        } catch (Exception ignored) {
            // Fallback legacy temporal por compatibilidad en despliegues mixtos.
            return IpcMessagePackCodec.readUtf8Fallback(new java.io.ByteArrayInputStream(raw));
        }
    }

    private int resolveCandlesPerDay(String timeframe) {
        return switch (timeframe == null ? "" : timeframe.toLowerCase()) {
            case "1m" -> 1440;
            case "5m" -> 288;
            case "15m" -> 96;
            case "1h" -> 24;
            case "4h" -> 6;
            case "1d" -> 1;
            default -> 288;
        };
    }
}