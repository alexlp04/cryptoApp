package com.bottrading.training.application.port.out;

import java.util.List;
import java.util.Map;

import com.bottrading.market.domain.Vela;
import com.bottrading.training.domain.OptimizationResult;

public interface OptimizationEnginePort {

    OptimizationResult ejecutarOptimizacion(String modelType,
            String strategyPath,
            Map<String, List<Vela>> symbolCandles,
            String timeframe,
            double minComposite,
            Integer warmupCandles,
            int nTrials,
            int cvFolds);
}
