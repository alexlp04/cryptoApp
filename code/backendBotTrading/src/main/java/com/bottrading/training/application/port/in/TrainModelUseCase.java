package com.bottrading.training.application.port.in;

import java.util.Map;

/**
 * Puerto de entrada para entrenamiento de modelos de IA.
 */
public interface TrainModelUseCase {

    String entrenarModelo(String nombreModelo, String timeframe, String symbol, int dias,
                          Map<String, Object> hyperparams, String strategyName);
}
