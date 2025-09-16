package com.bottrading.beans;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
    name = "velas",
    uniqueConstraints = {
        @UniqueConstraint(columnNames = {"symbol", "interval", "open_time"})
    }
)

public class Vela {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 10, nullable = false)
    private String symbol;

@Column(name = "time_interval", length = 10, nullable = false)
    private String interval;

    @Column(name = "open_time", nullable = false)
    private Long openTime;

    @Column(precision = 18, scale = 8)
    private BigDecimal open;

    @Column(precision = 18, scale = 8)
    private BigDecimal high;

    @Column(precision = 18, scale = 8)
    private BigDecimal low;

    @Column(precision = 18, scale = 8)
    private BigDecimal close;

    @Column(precision = 18, scale = 8)
    private BigDecimal volume;

    @Column(name = "close_time", nullable = false)
    private Long closeTime;

    @Column(name = "quote_volume", precision = 20, scale = 8)
    private BigDecimal quoteVolume;

    private Integer trades;

    @Column(name = "taker_base_volume", precision = 18, scale = 8)
    private BigDecimal takerBaseVolume;

    @Column(name = "taker_quote_volume", precision = 20, scale = 8)
    private BigDecimal takerQuoteVolume;

    // --- Getters y Setters ---

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public String getInterval() {
        return interval;
    }

    public void setInterval(String interval) {
        this.interval = interval;
    }

    public Long getOpenTime() {
        return openTime;
    }

    public void setOpenTime(Long openTime) {
        this.openTime = openTime;
    }

    public BigDecimal getOpen() {
        return open;
    }

    public void setOpen(BigDecimal open) {
        this.open = open;
    }

    public BigDecimal getHigh() {
        return high;
    }

    public void setHigh(BigDecimal high) {
        this.high = high;
    }

    public BigDecimal getLow() {
        return low;
    }

    public void setLow(BigDecimal low) {
        this.low = low;
    }

    public BigDecimal getClose() {
        return close;
    }

    public void setClose(BigDecimal close) {
        this.close = close;
    }

    public BigDecimal getVolume() {
        return volume;
    }

    public void setVolume(BigDecimal volume) {
        this.volume = volume;
    }

    public Long getCloseTime() {
        return closeTime;
    }

    public void setCloseTime(Long closeTime) {
        this.closeTime = closeTime;
    }

    public BigDecimal getQuoteVolume() {
        return quoteVolume;
    }

    public void setQuoteVolume(BigDecimal quoteVolume) {
        this.quoteVolume = quoteVolume;
    }

    public Integer getTrades() {
        return trades;
    }

    public void setTrades(Integer trades) {
        this.trades = trades;
    }

    public BigDecimal getTakerBaseVolume() {
        return takerBaseVolume;
    }

    public void setTakerBaseVolume(BigDecimal takerBaseVolume) {
        this.takerBaseVolume = takerBaseVolume;
    }

    public BigDecimal getTakerQuoteVolume() {
        return takerQuoteVolume;
    }

    public void setTakerQuoteVolume(BigDecimal takerQuoteVolume) {
        this.takerQuoteVolume = takerQuoteVolume;
    }

}
