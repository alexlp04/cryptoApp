package com.bottrading.strategy.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.bottrading.strategy.application.port.in.StrategyLifecycleUseCase;

import lombok.extern.slf4j.Slf4j;

/**
 * Engancha el ciclo de vida de las estrategias al de la aplicación (operación 24/7).
 *
 * <p>Apagar la app (exit, SIGTERM de docker stop, reinicio) PAUSA las estrategias —fondos en
 * reserva, reanudables con 'start' o solas al arrancar con autoresume— en vez de liquidarlas. Liquidar es una orden explícita ('term').
 * ContextClosedEvent se publica antes de destruir los beans, con la BBDD aún disponible.
 */
@Slf4j
@Component
public class StrategyLifecycleHooks {

    private final StrategyLifecycleUseCase lifecycle;
    private final boolean autoResume;

    /** autoResume (CRYPTOAPP_AUTORESUME): reanudar solas, al arrancar, las estrategias que estaban en marcha. */
    public StrategyLifecycleHooks(StrategyLifecycleUseCase lifecycle,
            @Value("${cryptoapp.autoresume:false}") boolean autoResume) {
        this.lifecycle = lifecycle;
        this.autoResume = autoResume;
    }

    /** Se publica antes de los CommandLineRunner: el REPL de AppBot bloquea y ApplicationReadyEvent no llegaría. */
    @EventListener(ApplicationStartedEvent.class)
    public void alArrancar() {
        lifecycle.reconciliarEstadoConRuntime();
        if (autoResume) {
            lifecycle.reanudarMarcadas();
        }
    }

    @EventListener(ContextClosedEvent.class)
    public void alApagar() {
        log.info("Apagando: pausando las estrategias activas (fondos en reserva)...");
        lifecycle.pausarPorApagado();
    }
}
