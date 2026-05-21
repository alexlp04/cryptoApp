package com.bottrading.trading.application.port.in;

import com.bottrading.trading.infrastructure.bridge.SignalDTO;

/**
 * Puerto de entrada para procesamiento de señales de paper trading.
 */
public interface ProcessSignalUseCase {

    void onSignal(Long instanciaId, SignalDTO signal);
}
