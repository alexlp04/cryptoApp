package com.bottrading.market.domain;

import java.io.Serializable;

/**
 * Clase DTO corregida siguiendo el estándar CamelCase de Java.
 */
public class VelaDTO implements Serializable {

    private Long id;
    private String symbol;
    private String timeInterval;
    private Long openTime;
    private String open;
    private String high;
    private String low;
    private String close;
    private String volume;
    private Long closeTime;
    private String quoteVolume;
    private Integer trades;
    private String takerBaseVolume;
    private String takerQuoteVolume;
    
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

    public String getTimeInterval() {
        return timeInterval;
    }

    public void setTimeInterval(String timeInterval) {
        this.timeInterval = timeInterval;
    }

    public Long getOpenTime() {
        return openTime;
    }

    public void setOpenTime(Long openTime) {
        this.openTime = openTime;
    }

    public String getOpen() {
        return open;
    }

    public void setOpen(String open) {
        this.open = open;
    }

    public String getHigh() {
        return high;
    }

    public void setHigh(String high) {
        this.high = high;
    }

    public String getLow() {
        return low;
    }

    public void setLow(String low) {
        this.low = low;
    }

    public String getClose() {
        return close;
    }

    public void setClose(String close) {
        this.close = close;
    }

    public String getVolume() {
        return volume;
    }

    public void setVolume(String volume) {
        this.volume = volume;
    }

    public Long getCloseTime() {
        return closeTime;
    }

    public void setCloseTime(Long closeTime) {
        this.closeTime = closeTime;
    }

    public String getQuoteVolume() {
        return quoteVolume;
    }

    public void setQuoteVolume(String quoteVolume) {
        this.quoteVolume = quoteVolume;
    }

    public Integer getTrades() {
        return trades;
    }

    public void setTrades(Integer trades) {
        this.trades = trades;
    }

    public String getTakerBaseVolume() {
        return takerBaseVolume;
    }

    public void setTakerBaseVolume(String takerBaseVolume) {
        this.takerBaseVolume = takerBaseVolume;
    }

    public String getTakerQuoteVolume() {
        return takerQuoteVolume;
    }

    public void setTakerQuoteVolume(String takerQuoteVolume) {
        this.takerQuoteVolume = takerQuoteVolume;
    }

    public String toString() {
        return "VelaDTO{" +
                "id=" + id +
                ", symbol='" + symbol + '\'' +
                ", timeInterval='" + timeInterval + '\'' +
                ", openTime=" + openTime +
                ", open='" + open + '\'' +
                ", high='" + high + '\'' +
                ", low='" + low + '\'' +
                ", close='" + close + '\'' +
                ", volume='" + volume + '\'' +
                ", closeTime=" + closeTime +
                ", quoteVolume='" + quoteVolume + '\'' +
                ", trades=" + trades +
                ", takerBaseVolume='" + takerBaseVolume + '\'' +
                ", takerQuoteVolume='" + takerQuoteVolume + '\'' +
                '}';
    }
}