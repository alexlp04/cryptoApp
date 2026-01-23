package com.bottrading.beans;

import java.math.BigDecimal;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "capital_reservado")
public class CapitalReservado extends BaseEntity {

    private Long walletId;
    private Long estrategiaId;

    private BigDecimal reservado;
    private BigDecimal comprometido; // órdenes abiertas
    private BigDecimal riesgoAbierto;

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

    public BigDecimal getReservado() {
        return reservado;
    }

    public void setReservado(BigDecimal reservado) {
        this.reservado = reservado;
    }

    public BigDecimal getComprometido() {
        return comprometido;
    }

    public void setComprometido(BigDecimal comprometido) {
        this.comprometido = comprometido;
    }

    public BigDecimal getRiesgoAbierto() {
        return riesgoAbierto;
    }

    public void setRiesgoAbierto(BigDecimal riesgoAbierto) {
        this.riesgoAbierto = riesgoAbierto;
    }

}
