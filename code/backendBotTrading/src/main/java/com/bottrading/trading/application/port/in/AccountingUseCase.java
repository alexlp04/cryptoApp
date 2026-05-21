package com.bottrading.trading.application.port.in;

import com.bottrading.strategy.domain.InstanciaEstrategia;

import java.math.BigDecimal;

/**
 * Puerto de entrada para operaciones de contabilidad de strategies.
 */
public interface AccountingUseCase {

    InstanciaEstrategia activateStrategy(long walletId, long estrategiaId, BigDecimal capital);

    void pauseStrategyTemporarily(Long walletId, Long estrategiaId);

    void closeStrategy(Long walletId, Long estrategiaId);

    void commitCapital(Long estrategiaId, BigDecimal margin, BigDecimal risk);

    void closeTrade(Long walletId, Long estrategiaId, BigDecimal margin, BigDecimal pnl, BigDecimal risk);
}
