package com.bottrading.domain.strategy;

/**
 * Estados válidos del ciclo de vida de una {@link InstanciaEstrategia}.
 * Stored en base de datos como STRING (valor del name()).
 */
public enum EstadoEstrategia {
    CREADA,
    ACTIVA,
    DETENIDA,
    TERMINADA
}
