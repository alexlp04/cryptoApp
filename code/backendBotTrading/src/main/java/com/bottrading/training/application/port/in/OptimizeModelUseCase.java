package com.bottrading.training.application.port.in;

/**
 * Puerto de entrada para optimización de hiperparámetros.
 */
public interface OptimizeModelUseCase {

    String optimizarHiperparametros(String nombreModelo, String timeframe, String symbol,
                                    int dias, String strategyName, double minAccuracy);
}
