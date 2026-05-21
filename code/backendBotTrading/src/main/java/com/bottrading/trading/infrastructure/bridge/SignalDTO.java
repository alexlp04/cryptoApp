package com.bottrading.trading.infrastructure.bridge;

import java.math.BigDecimal;

public class SignalDTO {

    private String type; // SIGNAL
    private String symbol;
    private String action; // BUY / SELL
    private BigDecimal price;
    private String timeframe;
    private long timestamp;
    private boolean isReal;

    public SignalDTO() {
        // Constructor vacío para serialización JSON
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
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

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public boolean isReal() {
        return isReal;
    }

    public void setReal(boolean isReal) {
        this.isReal = isReal;
    }

    public String getTimeframe() {
        return timeframe;
    }

    public void setTimeframe(String timeframe) {
        this.timeframe = timeframe;
    }

    @Override
    public String toString() {
        return "SignalDTO{" +
                "symbol='" + symbol + '\'' +
                ", action='" + action + '\'' +
                ", price=" + price +
                ", timestamp=" + timestamp +
                ", isReal=" + isReal +
                '}';
    }
}
