package com.bottrading.application.training;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.bottrading.application.market.MarketDataService;
import com.bottrading.config.ProcessExecutorConfig;
import com.bottrading.domain.market.IndicadorRepository;
import com.bottrading.domain.market.IndicadorTecnico;
import com.bottrading.domain.market.Vela;
import com.bottrading.domain.market.VelaRepository;
import com.bottrading.exceptions.StrategyExecutionException;
import com.bottrading.infrastructure.bridge.PythonBridgeExecutionException;
import com.bottrading.infrastructure.bridge.PythonBridgeFacade;
import com.bottrading.infrastructure.bridge.PythonBridgeRequest;
import com.bottrading.infrastructure.bridge.protocol.IpcMessagePackCodec;
import com.bottrading.infrastructure.bridge.protocol.IpcMessageType;
import com.bottrading.utils.PathConfig;
import com.bottrading.utils.StrategyInspector;
import com.google.gson.Gson;

import lombok.extern.slf4j.Slf4j;

/**
 * Servicio de aplicación encargado de orquestar la búsqueda de hiperparámetros
 * óptimos mediante Optuna (Python), reutilizando el mismo pipeline de preparación
 * de datos que AITrainingService.
 */
@Slf4j
@Service
public class AIOptimizationService {

    private static final int DEFAULT_N_TRIALS = 100;
    private static final int DEFAULT_CV_FOLDS = 5;
    private static final double DEFAULT_MIN_ACCURACY = 55.0;

    private final Gson gson = new Gson();

    private final MarketDataService marketDataService;
    private final VelaRepository velaRepo;
    private final IndicadorRepository indicadorRepo;
    private final PythonBridgeFacade pythonBridgeFacade;

    public AIOptimizationService(MarketDataService marketDataService, VelaRepository velaRepo,
            IndicadorRepository indicadorRepo, PythonBridgeFacade pythonBridgeFacade) {
        this.marketDataService = marketDataService;
        this.velaRepo = velaRepo;
        this.indicadorRepo = indicadorRepo;
        this.pythonBridgeFacade = pythonBridgeFacade;
    }

    /**
     * Orquesta la búsqueda de hiperparámetros óptimos para el modelo dado.
     *
     * @param nombreModelo   tipo de modelo (xgboost, lightgbm, random_forest, neural_network)
     * @param timeframe      timeframe de las velas (1m, 5m, 15m, 1h, 4h, 1d)
     * @param symbol         símbolo del par de trading (ej: BTCUSDT)
     * @param dias           número de días históricos a usar
     * @param strategyName   nombre de la estrategia Python (puede ser null)
     * @param minAccuracy    accuracy mínima requerida en porcentaje (ej: 60.0 → 0.60)
     * @return resultado formateado con mejores hiperparámetros y métricas
     */
    public String optimizarHiperparametros(String nombreModelo, String timeframe, String symbol,
            int dias, String strategyName, double minAccuracy) {
        return ejecutarOptimizacion(nombreModelo, timeframe, symbol, dias, strategyName, minAccuracy);
    }

    private String ejecutarOptimizacion(String nombreModelo, String timeframe, String symbol,
            int dias, String strategyName, double minAccuracy) {
        try {
            long now = System.currentTimeMillis();
            final boolean useDynamicStrategy = strategyName != null && !strategyName.isBlank();

            Integer warmupCandles = null;
            Integer totalCandles = null;
            int daysForPreparation = dias;

            if (useDynamicStrategy) {
                try {
                    warmupCandles = StrategyInspector.getWarmupPeriod(strategyName);
                    totalCandles = StrategyInspector.getCandlesRequired(strategyName, timeframe, dias);
                } catch (Exception e) {
                    throw new StrategyExecutionException(
                        "Error al inspeccionar estrategia '" + strategyName + "': " +
                        e.getMessage() + ". ¿Existe el archivo " + strategyName + ".py en la carpeta de estrategias?",
                        e
                    );
                }

                if (warmupCandles == null || totalCandles == null) {
                    throw new StrategyExecutionException(
                        "La estrategia '" + strategyName + "' no devolvió warmup_period o candles_required. " +
                        "Verifica que implemente estos métodos correctamente."
                    );
                }

                int candlesPerDay = resolveCandlesPerDay(timeframe);
                daysForPreparation = (int) Math.ceil((double) totalCandles / candlesPerDay);
            }

            marketDataService.prepararDatosParaEntrenamiento(symbol, timeframe, daysForPreparation, now);

            log.info("Extrayendo dataset para optimización — modo: {}",
                    useDynamicStrategy ? "estrategia dinámica (" + strategyName + ")" : "indicadores técnicos");

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
                return "Error: No hay datos suficientes de " + symbol + " para optimizar.";
            }

            List<Map<String, Object>> dataset = construirDataset(velas, useDynamicStrategy, indicadorRepo);

            log.info("--- DATASET LISTO --- {} registros para optimización", dataset.size());

            Map<String, Object> payload = construirPayload(nombreModelo, symbol, timeframe, dataset,
                    strategyName, warmupCandles, useDynamicStrategy, minAccuracy);

            String jsonPayload = gson.toJson(payload);

            log.info("Enviando {} registros al motor de optimización (Python)...", dataset.size());

            velas.clear();
            dataset.clear();

            return invocarMotorOptimizacion(jsonPayload);

        } catch (StrategyExecutionException e) {
            throw e;
        } catch (Exception e) {
            log.error("Fallo durante optimización de hiperparámetros: {}", e.getMessage(), e);
            throw new StrategyExecutionException("Error crítico optimizando hiperparámetros: " + e.getMessage(), e);
        }
    }

    private List<Map<String, Object>> construirDataset(List<Vela> velas, boolean useDynamicStrategy,
            IndicadorRepository indicadorRepo) {
        List<Map<String, Object>> dataset = new ArrayList<>();

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
            log.info("Extraídas {} velas. Obteniendo indicadores en bloque...", velas.size());
            List<IndicadorTecnico> todosLosIndicadores = indicadorRepo.findByVelaIn(velas);
            Map<Long, List<IndicadorTecnico>> indicadoresPorVela = todosLosIndicadores.stream()
                    .collect(Collectors.groupingBy(ind -> ind.getVela().getId()));

            for (Vela v : velas) {
                Map<String, Object> row = new HashMap<>();
                row.put("timestamp", v.getOpenTime());
                row.put("close", v.getClose());
                row.put("volume", v.getVolume());

                List<IndicadorTecnico> indicadoresVela = indicadoresPorVela
                        .getOrDefault(v.getId(), new ArrayList<>());
                for (IndicadorTecnico ind : indicadoresVela) {
                    row.put(ind.getTipo(), ind.getValor());
                }
                dataset.add(row);
            }
        }
        return dataset;
    }

    private Map<String, Object> construirPayload(String nombreModelo, String symbol, String timeframe,
            List<Map<String, Object>> dataset, String strategyName, Integer warmupCandles,
            boolean useDynamicStrategy, double minAccuracy) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("model_type", nombreModelo);
        payload.put("symbol", symbol);
        payload.put("timeframe", timeframe);
        payload.put("dataset", dataset);
        payload.put("min_accuracy", minAccuracy / 100.0);
        payload.put("n_trials", DEFAULT_N_TRIALS);
        payload.put("cv_folds", DEFAULT_CV_FOLDS);

        if (useDynamicStrategy) {
            payload.put("strategy_name", strategyName);
            payload.put("warmup_candles", warmupCandles);
        }
        return payload;
    }

    /**
     * Invoca el motor Python de optimización con timeout extendido (OPTIMIZE_INACTIVITY_TIMEOUT_SECONDS).
     */
    private String invocarMotorOptimizacion(String jsonPayload) {
        try {
            long startTime = System.currentTimeMillis();
            log.debug("Iniciando optimización con payload de {} bytes", jsonPayload.length());

            PythonBridgeRequest<String> request = PythonBridgeRequest.<String>builder(PathConfig.ENGINE_OPTIMIZE_PATH)
                    .operationName("optimize-hyperparams")
                    .noTimeout()
                    .inactivityTimeout(ProcessExecutorConfig.OPTIMIZE_INACTIVITY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .maxRetries(ProcessExecutorConfig.MAX_RETRIES)
                    .retryDelayMs(ProcessExecutorConfig.RETRY_DELAY_MS)
                    .stdinWriter(os -> escribirEnvelopeOptimize(os, jsonPayload))
                    .stdoutReader(this::leerEnvelopeOptimize)
                    .onStderrLine(line -> log.info("PY [optimize]: {}", line))
                    .build();

            String stdout = pythonBridgeFacade.execute(request);

            long duration = System.currentTimeMillis() - startTime;
            log.info("Optimización completada en {} ms", duration);

            return stdout;
        } catch (PythonBridgeExecutionException e) {
            throw new StrategyExecutionException("Optimización falló: " + e.getMessage(), e);
        } catch (StrategyExecutionException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error inesperado en optimización: {}", e.getMessage(), e);
            throw new StrategyExecutionException("Error inesperado: " + e.getMessage(), e);
        }
    }

    private void escribirEnvelopeOptimize(java.io.OutputStream outputStream, String jsonPayload) throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = gson.fromJson(jsonPayload, Map.class);
        IpcMessagePackCodec.writeEnvelope(outputStream, IpcMessageType.OPTIMIZE_REQUEST, payload);
    }

    private String leerEnvelopeOptimize(InputStream inputStream) throws IOException {
        Map<String, Object> envelope = IpcMessagePackCodec.readEnvelope(inputStream);
        Object payload = envelope.get("payload");
        if (payload == null) {
            return "Sin respuesta del motor de optimización.";
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> resultado = (Map<String, Object>) payload;
        return formatearResultadoOptimizacion(resultado);
    }

    private String formatearResultadoOptimizacion(Map<String, Object> r) {
        StringBuilder sb = new StringBuilder();
        String status = String.valueOf(r.getOrDefault("status", "unknown")).toUpperCase();
        boolean reached = Boolean.TRUE.equals(r.get("min_accuracy_reached"));

        sb.append("Estado       : ").append(status)
          .append(" | Min. accuracy requerida: ").append(r.getOrDefault("min_accuracy_reached", "?"))
          .append(" (").append(reached ? "ALCANZADA" : "NO ALCANZADA").append(")\n");

        Object trialsComp = r.get("trials_completed");
        Object trialsTotal = r.get("trials_total");
        sb.append("Trials       : ").append(trialsComp).append(" / ").append(trialsTotal).append(" completados\n");

        sb.append("\n--- MEJOR CONFIGURACION (multi-metrica) ---\n");
        sb.append("Score compuesto   : ").append(r.getOrDefault("best_composite_score", "?")).append("\n");
        sb.append("F1 CV             : ").append(r.getOrDefault("best_f1_cv", "?")).append("\n");
        sb.append("Accuracy CV       : ").append(r.getOrDefault("best_accuracy_cv_pct", "?")).append("%\n");
        sb.append("Accuracy Train    : ")
          .append(formatPct(r.get("best_accuracy_train"))).append("%\n");
        sb.append("Win Rate CV (sim) : ").append(formatPct(r.get("best_win_rate_cv"))).append("%\n");
        sb.append("Overfit gap       : ").append(r.getOrDefault("overfit_gap", "?")).append("\n");

        Object bestParams = r.get("best_params");
        if (bestParams instanceof Map<?, ?> paramsMap) {
            sb.append("\nHiperparametros optimos:\n");
            paramsMap.forEach((k, v) -> sb.append("  ").append(k).append(" = ").append(v).append("\n"));
        }

        sb.append("\n--- METRICAS DEL MODELO FINAL (conjunto test 20%) ---\n");
        Object fm = r.get("final_metrics");
        if (fm instanceof Map<?, ?> fmMap) {
            sb.append("Accuracy  : ").append(fmMap.get("accuracy")).append("%\n");
            sb.append("Precision : ").append(fmMap.get("precision")).append("%\n");
            sb.append("Recall    : ").append(fmMap.get("recall")).append("%\n");
            sb.append("F1        : ").append(fmMap.get("f1")).append("%\n");
        }

        sb.append("\n--- SIMULACION DE TRADING (conjunto test 20%) ---\n");
        Object sim = r.get("trading_simulation_test");
        if (sim instanceof Map<?, ?> simMap) {
            sb.append("Trades totales  : ").append(simMap.get("n_trades")).append("\n");
            sb.append("Ganadas / Perdidas: ").append(simMap.get("n_wins"))
              .append(" / ").append(simMap.get("n_losses")).append("\n");
            sb.append("Win Rate        : ").append(simMap.get("win_rate")).append("%\n");
            sb.append("Profit Factor   : ").append(simMap.get("profit_factor")).append("\n");
            sb.append("Sharpe Ratio    : ").append(simMap.get("sharpe")).append("\n");
            sb.append("Retorno         : ").append(simMap.get("retorno_pct")).append("%\n");
            sb.append("Capital inicial : ").append(simMap.get("capital_inicial")).append("\n");
            sb.append("Capital final   : ").append(simMap.get("capital_final")).append("\n");
        }

        Object modelSaved = r.get("model_saved_at");
        if (modelSaved != null) {
            sb.append("\nModelo guardado : ").append(modelSaved).append("\n");
        }
        Object csvPath = r.get("csv_path");
        if (csvPath != null) {
            sb.append("CSV trials      : ").append(csvPath).append("\n");
        }
        Object tradesCsvPath = r.get("trades_csv_path");
        if (tradesCsvPath != null && !String.valueOf(tradesCsvPath).isBlank()) {
            sb.append("CSV trades      : ").append(tradesCsvPath).append("\n");
        }

        Object dataInfo = r.get("data_info");
        if (dataInfo instanceof Map<?, ?> di) {
            sb.append("\n--- INFORMACION DEL DATASET ---\n");
            sb.append("Modelo          : ").append(di.get("modelo_usado")).append("\n");
            sb.append("Total filas     : ").append(di.get("total_filas")).append("\n");
            sb.append("Entrenamiento   : ").append(di.get("filas_entrenamiento")).append(" filas (80%)\n");
            sb.append("Test            : ").append(di.get("filas_test")).append(" filas (20%)\n");
            sb.append("Features        : ").append(di.get("num_features")).append("\n");
            sb.append("CV folds        : ").append(di.get("cv_folds")).append("\n");
            Object pesos = di.get("objetivo_pesos");
            if (pesos instanceof Map<?, ?> pm) {
                sb.append("Objetivo pesos  : F1=").append(pm.get("F1_cv"))
                  .append(" WinRate=").append(pm.get("win_rate"))
                  .append(" ProfitFactor=").append(pm.get("profit_factor"))
                  .append(" Sharpe=").append(pm.get("sharpe")).append("\n");
            }
        }

        return sb.toString();
    }

    private String formatPct(Object val) {
        if (val == null) return "?";
        try {
            return String.format("%.2f", Double.parseDouble(val.toString()) * 100.0);
        } catch (NumberFormatException e) {
            return val.toString();
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
