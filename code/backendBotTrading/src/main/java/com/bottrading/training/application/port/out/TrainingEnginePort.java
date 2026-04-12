package com.bottrading.training.application.port.out;

import java.util.List;
import java.util.Map;

import com.bottrading.market.domain.Vela;
import com.bottrading.training.domain.TrainingResult;

public interface TrainingEnginePort {

    /**
     * Sends candle data and strategy path to the Python engine.
     * Python loads the strategy, computes its indicators internally,
     * trains the model, and returns the result.
     *
     * @param strategyPath  absolute path to the Python strategy file
     * @param symbolCandles raw candle data per symbol, already fetched from DB
     * @param timeframe     timeframe string (e.g. "1h", "4h")
     * @return training result: model path, metrics, or error detail
     */
    default TrainingResult ejecutarEntrenamiento(String strategyPath,
            Map<String, List<Vela>> symbolCandles,
            String timeframe) {
        return ejecutarEntrenamiento("random_forest", Map.of(), strategyPath, symbolCandles, timeframe);
    }

    TrainingResult ejecutarEntrenamiento(String modelType,
            Map<String, Object> hyperparameters,
            String strategyPath,
            Map<String, List<Vela>> symbolCandles,
            String timeframe);
}