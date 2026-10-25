package com.bottrading.trading.domain;

import java.math.BigDecimal;

import com.bottrading.shared.domain.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * Señal fallida pendiente de reintento, persistida para sobrevivir a reinicios
 * de la JVM (durabilidad de la cola de reintentos).
 *
 * <p>Sigue el soft-delete de {@link BaseEntity}: al aplicarse con éxito se marca
 * {@code eliminado = true} en lugar de borrarse físicamente.
 */
@Entity
@Table(name = "senal_fallida_pendiente")
public class SenalFallidaPendiente extends BaseEntity {

    @Column(nullable = false)
    private Long instanciaId;

    /** Clave determinista {@code symbol|timeframe|action|timestamp} para localizar la señal. */
    @Column(nullable = false, length = 160)
    private String claveSenal;

    private String symbol;
    private String action;

    @Column(precision = 20, scale = 8)
    private BigDecimal price;

    private String timeframe;

    @Column(name = "signal_timestamp")
    private long signalTimestamp;

    private boolean realSignal;

    public Long getInstanciaId() {
        return instanciaId;
    }

    public void setInstanciaId(Long instanciaId) {
        this.instanciaId = instanciaId;
    }

    public String getClaveSenal() {
        return claveSenal;
    }

    public void setClaveSenal(String claveSenal) {
        this.claveSenal = claveSenal;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public String getTimeframe() {
        return timeframe;
    }

    public void setTimeframe(String timeframe) {
        this.timeframe = timeframe;
    }

    public long getSignalTimestamp() {
        return signalTimestamp;
    }

    public void setSignalTimestamp(long signalTimestamp) {
        this.signalTimestamp = signalTimestamp;
    }

    public boolean isRealSignal() {
        return realSignal;
    }

    public void setRealSignal(boolean realSignal) {
        this.realSignal = realSignal;
    }
}
