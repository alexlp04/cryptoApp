package com.bottrading.training.application.port.out;

import java.util.List;
import java.util.Map;

import com.bottrading.market.domain.Vela;
import com.bottrading.training.domain.TrainingResult;

public interface TrainingEnginePort {

    /**
     * @param warmupCandles velas iniciales que la estrategia necesita para que sus
     *                      indicadores esten formados. El engine las descarta del dataset
     *                      de entrenamiento. Debe enviarse siempre: si no llega, el modelo
     *                      se entrena sobre velas con indicadores a medio formar.
     */
    TrainingResult ejecutarEntrenamiento(String modelType,
            Map<String, Object> hyperparameters,
            String strategyPath,
            Map<String, List<Vela>> symbolCandles,
            String timeframe,
            int warmupCandles);
}