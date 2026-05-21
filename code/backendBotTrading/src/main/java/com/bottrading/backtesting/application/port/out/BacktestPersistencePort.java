package com.bottrading.backtesting.application.port.out;

/**
 * Puerto de salida para persistencia de resultados de backtest (CSV, estadísticas).
 */
public interface BacktestPersistencePort {

    void limpiarResultadosPrevios(String strategyName);
}
