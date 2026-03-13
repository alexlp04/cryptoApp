package com.bottrading.beans;

public enum LedgerType {
    AVAILABLE,
    RESERVED,
    COMMITTED,
    MARGIN_COMMITTED,      // Nuevo: Capital comprometido en margen
    REALIZED_PNL,
    UNREALIZED_PNL,
    FEE,
    FUNDING,
    STRATEGY_PAUSED         // Nuevo: Meta-data de pausa estrategia
}

