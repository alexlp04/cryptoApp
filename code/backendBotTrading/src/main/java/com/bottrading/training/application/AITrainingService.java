package com.bottrading.training.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.bottrading.market.application.port.in.FetchMarketDataUseCase;
import com.bottrading.market.domain.Timeframe;
import com.bottrading.market.domain.Vela;
import com.bottrading.market.domain.VelaRepository;
import com.bottrading.shared.exceptions.StrategyExecutionException;
import com.bottrading.shared.utils.ConsoleLoader;
import com.bottrading.shared.utils.PathConfig;
import com.bottrading.strategy.infrastructure.StrategyInspector;
import com.bottrading.training.application.port.in.TrainModelUseCase;
import com.bottrading.training.application.port.out.TrainingEnginePort;
import com.bottrading.training.domain.TrainingResult;
import com.google.gson.Gson;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class AITrainingService implements TrainModelUseCase {

    private final Gson gson = new Gson();

    private final FetchMarketDataUseCase fetchMarketDataUseCase;
    private final VelaRepository velaRepository;
    private final TrainingEnginePort trainingEnginePort;

    @Override
    public String entrenarModelo(String nombreModelo, String timeframe, String symbol, int dias,
            Map<String, Object> hyperparams, String strategyName) {
        try {
            return ejecutarEntrenamiento(nombreModelo, timeframe, symbol, dias, hyperparams, strategyName);
        } catch (StrategyExecutionException e) {
            throw e;
        } catch (Exception e) {
            log.error("Fallo durante entrenamiento: {}", e.getMessage(), e);
            throw new StrategyExecutionException("Error crítico entrenando modelo: " + e.getMessage(), e);
        }
    }

    private String ejecutarEntrenamiento(String nombreModelo, String timeframe, String symbol, int dias,
            Map<String, Object> hyperparams, String strategyName) {
        ConsoleLoader loader = ConsoleLoader.getInstance();
        if (strategyName == null || strategyName.isBlank()) {
            throw new StrategyExecutionException(
                    "El entrenamiento requiere una estrategia (--strategy). "
                    + "Los indicadores se calculan siempre vía populate_indicators() de la estrategia.");
        }

        loader.startSpinner("Validando estrategia y calculando ventana de datos...");
        log.info("[train] Inicio entrenamiento model={} symbol={} timeframe={} dias={} strategy={}",
            nombreModelo, symbol, timeframe, dias, strategyName);

        long now = System.currentTimeMillis();
        TrainingWindow trainingWindow = resolverVentanaEntrenamiento(strategyName, timeframe, dias);

        loader.updateMessage("Descargando y actualizando velas históricas...");
        log.info("[train] Ventana resuelta: dias_efectivos={} strategy_path={}",
            trainingWindow.daysForPreparation(), trainingWindow.strategyPath());

        fetchMarketDataUseCase.fetchIncremental(symbol, timeframe, trainingWindow.daysForPreparation(), now);

        // fetchIncremental detiene el ConsoleLoader internamente — reiniciamos el spinner
        loader.startSpinner("Preparando dataset de entrenamiento...");
        long targetTimestamp = now - (trainingWindow.daysForPreparation() * 24L * 60L * 60L * 1000L);
        List<Vela> velas = velaRepository.findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(
                symbol, timeframe, targetTimestamp);

        log.info("[train] Velas disponibles tras fetch incremental: {}", velas.size());

        if (velas.isEmpty()) {
            loader.stop("Sin datos suficientes para entrenar.");
            return "Error: No hay datos suficientes de " + symbol + " para entrenar.";
        }

        String strategyPath = trainingWindow.strategyPath();
        Map<String, List<Vela>> candlesBySymbol = Map.of(symbol, velas);

        log.info("Entrenando modelo {} con {} velas crudas para {}:{}{}",
                nombreModelo,
                velas.size(),
                symbol,
                timeframe,
                strategyPath == null ? "" : " usando estrategia " + strategyName);

        loader.updateMessage("Entrenando modelo con " + velas.size() + " velas — puede tardar varios minutos...");

        TrainingResult result = trainingEnginePort.ejecutarEntrenamiento(
                nombreModelo,
                hyperparams == null ? Map.of() : hyperparams,
                strategyPath,
                candlesBySymbol,
                timeframe,
                trainingWindow.warmupCandles());

        if (!result.success()) {
            loader.stop("El entrenamiento no pudo completarse.");
            throw new StrategyExecutionException("Entrenamiento IA falló: " + result.errorMessage());
        }

        loader.updateMessage("Procesando métricas y construyendo respuesta...");
        log.info("[train] Entrenamiento finalizado con éxito. model_path={} metrics_keys={}",
                result.modelPath(),
                result.metrics() == null ? "[]" : result.metrics().keySet());
        loader.stop("✅ Entrenamiento completado con éxito.");

        return formatearResultado(nombreModelo, symbol, timeframe, velas.size(), result);
    }

    private TrainingWindow resolverVentanaEntrenamiento(String strategyName, String timeframe, int dias) {
        if (strategyName == null || strategyName.isBlank()) {
            return new TrainingWindow(dias, null, 0);
        }

        try {
            int warmupCandles = StrategyInspector.getWarmupPeriod(strategyName);
            int totalCandles = StrategyInspector.getCandlesRequired(timeframe, dias, warmupCandles);
            int daysForPreparation = (int) Math.ceil(
                    (double) totalCandles / Timeframe.velasPorDiaOrDefault(timeframe));
            String strategyPath = PathConfig.getValidStrategyPath(strategyName);

            log.info("Modo estrategia dinámica: strategy='{}', warmup={}, velas_requeridas={}, dias_efectivos={}",
                    strategyName, warmupCandles, totalCandles, daysForPreparation);

            return new TrainingWindow(daysForPreparation, strategyPath, warmupCandles);
        } catch (Exception e) {
            throw new StrategyExecutionException(
                    "Error al inspeccionar estrategia '" + strategyName + "': " + e.getMessage(),
                    e);
        }
    }

    private String formatearResultado(String nombreModelo, String symbol, String timeframe, int candles,
            TrainingResult result) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("status", "success");
        output.put("model_type", nombreModelo);
        output.put("symbol", symbol);
        output.put("timeframe", timeframe);
        output.put("candles", candles);
        output.put("model_path", result.modelPath());
        output.put("metrics", result.metrics());
        if (result.tradingSimulation() != null && !result.tradingSimulation().isEmpty()) {
            output.put("trading_simulation_test", result.tradingSimulation());
        }
        return gson.toJson(output);
    }

    private record TrainingWindow(int daysForPreparation, String strategyPath, int warmupCandles) {
    }
}