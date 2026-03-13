package com.bottrading.beans;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

@Entity
@Table(name = "ledger_entry")
public class LedgerEntry extends BaseEntity {

    private Long walletId;
    private Long estrategiaId;

    @Enumerated(EnumType.STRING)
    private LedgerType type;

    private BigDecimal amount;

    private BigDecimal balanceAfter;

    private String reference; // orderId, tradeId, etc

    private Instant timestamp;

    public Long getWalletId() {
        return walletId;
    }

    public void setWalletId(Long walletId) {
        this.walletId = walletId;
    }

    public Long getEstrategiaId() {
        return estrategiaId;
    }

    public void setEstrategiaId(Long estrategiaId) {
        this.estrategiaId = estrategiaId;
    }

    public LedgerType getType() {
        return type;
    }

    public void setType(LedgerType type) {
        this.type = type;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public BigDecimal getBalanceAfter() {
        return balanceAfter;
    }

    public void setBalanceAfter(BigDecimal balanceAfter) {
        this.balanceAfter = balanceAfter;
    }

    public String getReference() {
        return reference;
    }

    public void setReference(String reference) {
        this.reference = reference;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

}
