package com.bottrading.training.application;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.bottrading.market.application.port.in.FetchMarketDataUseCase;
import com.bottrading.market.domain.Vela;
import com.bottrading.market.domain.VelaRepository;
import com.bottrading.shared.exceptions.StrategyExecutionException;
import com.bottrading.shared.utils.ConsoleLoader;
import com.bottrading.shared.utils.PathConfig;
import com.bottrading.strategy.infrastructure.StrategyInspector;
import com.bottrading.training.application.port.in.OptimizeModelUseCase;
import com.bottrading.training.application.port.out.OptimizationEnginePort;
import com.bottrading.training.domain.OptimizationResult;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Servicio de aplicación que orquesta la búsqueda de hiperparámetros óptimos
 * mediante Optuna (Python). Los indicadores técnicos se calculan directamente
 * en engine_optimize.py, eliminando el round-trip IPC+BD previo.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AIOptimizationService implements OptimizeModelUseCase {

    private final FetchMarketDataUseCase fetchMarketDataUseCase;
    private final VelaRepository velaRepo;
    private final OptimizationEnginePort optimizationEnginePort;

    @Override
    public String optimizarHiperparametros(String nombreModelo, String timeframe, String symbol,
            int dias, String strategyName, double minComposite,
            int nTrials, int cvFolds) {
        try {
            return ejecutarOptimizacion(nombreModelo, timeframe, symbol, dias, strategyName, minComposite, nTrials, cvFolds);
        } catch (StrategyExecutionException e) {
            throw e;
        } catch (Exception e) {
            log.error("Fallo durante optimización de hiperparámetros: {}", e.getMessage(), e);
            throw new StrategyExecutionException("Error crítico optimizando hiperparámetros: " + e.getMessage(), e);
        }
    }

    private String ejecutarOptimizacion(String nombreModelo, String timeframe, String symbol,
            int dias, String strategyName, double minComposite, int nTrials, int cvFolds) {
        ConsoleLoader loader = ConsoleLoader.getInstance();
        if (strategyName == null || strategyName.isBlank()) {
            throw new StrategyExecutionException(
                    "La optimización requiere una estrategia (--strategy). "
                    + "Los indicadores se calculan siempre vía populate_indicators() de la estrategia.");
        }

        loader.startSpinner("Validando estrategia y configuración inicial...");
        log.info("[optimize] Inicio optimización model={} symbol={} timeframe={} dias={} strategy={} minComposite={}%%",
            nombreModelo, symbol, timeframe, dias, strategyName, minComposite);

        long now = System.currentTimeMillis();
        final boolean useDynamicStrategy = true;

        Integer warmupCandles = null;
        int daysForPreparation = dias;
        String strategyPath = null;

        if (useDynamicStrategy) {
            try {
                loader.updateMessage("Calculando ventana de datos necesaria para la estrategia...");
                warmupCandles = StrategyInspector.getWarmupPeriod(strategyName);
                int totalCandles = StrategyInspector.getCandlesRequired(strategyName, timeframe, dias);
                int candlesPerDay = resolveCandlesPerDay(timeframe);
                daysForPreparation = (int) Math.ceil((double) totalCandles / candlesPerDay);
                strategyPath = PathConfig.getValidStrategyPath(strategyName);
            } catch (Exception e) {
                throw new StrategyExecutionException(
                        "Error al inspeccionar estrategia '" + strategyName + "': " + e.getMessage(), e);
            }

            log.info("Modo estrategia dinámica: strategy='{}', warmup={}, dias_efectivos={}",
                    strategyName, warmupCandles, daysForPreparation);
        }

        loader.updateMessage("Descargando y actualizando velas históricas...");
        fetchMarketDataUseCase.fetchIncremental(symbol, timeframe, daysForPreparation, now);

        loader.startSpinner("Preparando dataset de entrenamiento...");
        long targetTimestamp = now - (daysForPreparation * 24L * 60L * 60L * 1000L);
        List<Vela> velas = velaRepo.findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(
                symbol, timeframe, targetTimestamp);

        log.info("[optimize] Velas disponibles tras fetch incremental: {}", velas.size());

        if (velas.isEmpty()) {
            loader.stop("Sin datos suficientes para optimizar.");
            return "Error: No hay datos suficientes de " + symbol + " para optimizar.";
        }

        log.info("Enviando {} velas crudas al motor de optimización — modo: {}",
                velas.size(), useDynamicStrategy ? "estrategia dinámica (" + strategyName + ")" : "legacy");

        loader.updateMessage("Buscando hiperparámetros óptimos ("
                + velas.size() + " velas) — puede tardar varios minutos...");

        OptimizationResult result = optimizationEnginePort.ejecutarOptimizacion(
                nombreModelo,
                strategyPath,
                Map.of(symbol, velas),
                timeframe,
                minComposite / 100.0,
                warmupCandles,
                nTrials,
                cvFolds);

        if (!result.success()) {
            loader.stop("La optimización no pudo completarse.");
            throw new StrategyExecutionException("Optimización IA falló: " + result.errorMessage());
        }

        loader.updateMessage("Procesando resultados...");
        log.info("[optimize] Optimización completada. status={} trials={}/{} best_cv={}%%",
                result.resultData().getOrDefault("status", "unknown"),
                result.resultData().getOrDefault("trials_completed", "?"),
                result.resultData().getOrDefault("trials_total", "?"),
                result.resultData().getOrDefault("best_accuracy_cv_pct", "?"));
        loader.stop("✅ Optimización completada.");

        return formatearResultadoOptimizacion(result.resultData());
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
                        sb.append("Trades totales  : ").append(simMap.get("op_totales")).append("\n");
                        sb.append("Ganadas / Perdidas: ").append(simMap.get("op_ganadas"))
                            .append(" / ").append(simMap.get("op_perdidas")).append("\n");
            sb.append("Win Rate        : ").append(simMap.get("win_rate")).append("%\n");
            sb.append("Profit Factor   : ").append(simMap.get("profit_factor")).append("\n");
            sb.append("Sharpe Ratio    : ").append(simMap.get("sharpe")).append("\n");
                        sb.append("Retorno acum.   : ").append(simMap.get("retorno_acumulado")).append("%\n");
                        sb.append("Retorno total   : ").append(simMap.get("retorno_total")).append("\n");
                        sb.append("Max drawdown    : ").append(simMap.get("max_drawdown")).append("\n");
        }

        Object modelSaved = r.get("model_saved_at");
        if (modelSaved != null) {
            sb.append("\nModelo guardado : ").append(modelSaved).append("\n");
        }
        Object csvPath = r.get("csv_path");
        if (csvPath != null) {
            sb.append("CSV trials      : ").append(csvPath).append("\n");
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

