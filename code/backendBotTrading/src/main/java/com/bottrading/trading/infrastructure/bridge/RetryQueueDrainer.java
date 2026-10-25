package com.bottrading.trading.infrastructure.bridge;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.bottrading.trading.application.port.in.ProcessSignalUseCase;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Drena periódicamente la cola de señales fallidas, reintentándolas sin esperar
 * al cierre de la estrategia. Complementa a {@link SignalRetryQueueService}, que
 * solo drenaba en el cierre.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetryQueueDrainer {

    private final SignalRetryQueueService retryQueueService;
    private final ProcessSignalUseCase processSignalUseCase;

    /**
     * Reintenta las señales pendientes de cada instancia cada cierto intervalo
     * (configurable con {@code cryptoapp.retry.drain-interval-ms}, por defecto 30 s).
     */
    @Scheduled(
            fixedDelayString = "${cryptoapp.retry.drain-interval-ms:30000}",
            initialDelayString = "${cryptoapp.retry.drain-initial-delay-ms:30000}")
    public void drenarPeriodicamente() {
        var instancias = retryQueueService.instanciasConPendientes();
        if (instancias.isEmpty()) {
            return;
        }
        log.debug("Drenado periódico de reintentos para {} estrategia(s)", instancias.size());
        for (Long instanciaId : instancias) {
            try {
                retryQueueService.procesarSignalesPendientes(instanciaId, processSignalUseCase);
            } catch (Exception e) {
                log.warn("Error drenando reintentos de estrategia {}: {}", instanciaId, e.getMessage());
            }
        }
    }
}
