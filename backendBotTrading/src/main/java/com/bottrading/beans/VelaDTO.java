package com.bottrading.beans;

public class VelaDTO {
    public String symbol;
    public String time_interval;
    public Long open_time;
    public String open;
    public String high;
    public String low;
    public String close;
    public String volume;
    public Long close_time;
    public String quote_volume;
    public Integer trades;
    public String taker_base_volume;
    public String taker_quote_volume;


    public String getSymbol() {
        return symbol;
    }
    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }
    public String getTimeInterval() {
        return time_interval;
    }
    public void setTimeInterval(String time_interval) {
        this.time_interval = time_interval;
    }
    public Long getOpenTime() {
        return open_time;
    }
    public void setOpenTime(Long open_time) {
        this.open_time = open_time;
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
        return close_time;
    }
    public void setCloseTime(Long close_time) {
        this.close_time = close_time;
    }
    public String getQuoteVolume() {
        return quote_volume;
    }
    public void setQuoteVolume(String quote_volume) {
        this.quote_volume = quote_volume;
    }
    public Integer getTrades() {
        return trades;
    }
    public void setTrades(Integer trades) {
        this.trades = trades;
    }
    public String getTakerBaseVolume() {
        return taker_base_volume;
    }
    public void setTakerBaseVolume(String taker_base_volume) {
        this.taker_base_volume = taker_base_volume;
    }
    public String getTakerQuoteVolume() {
        return taker_quote_volume;
    }
    public void setTakerQuoteVolume(String taker_quote_volume) {
        this.taker_quote_volume = taker_quote_volume;
    }

    // Constructor, getters y setters si es necesario

    
}
