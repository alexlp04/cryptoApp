package com.bottrading.trading.infrastructure.bridge.protocol;

/**
 * Tipos de mensaje IPC soportados por los engines actuales.
 */
public enum IpcMessageType {
    FETCH_REQUEST,
    FETCH_CHUNK,
    FETCH_RESPONSE,
    INDICATORS_REQUEST,
    INDICATORS_RESPONSE,
    TRAIN_REQUEST,
    TRAIN_RESPONSE,
    OPTIMIZE_REQUEST,
    OPTIMIZE_RESPONSE,
    PREDICT_REQUEST,
    PREDICT_RESPONSE,
    BACKTEST_REQUEST,
    BACKTEST_RESPONSE,
    RT_SIGNAL,
    RT_LOG,
    ERROR
}
