package com.bottrading.trading.infrastructure.bridge;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

// Python añade campos (source…) que Java no usa: no deben invalidar la señal.
@JsonIgnoreProperties(ignoreUnknown = true)
public class SignalDTO {

    private String type; // SIGNAL
    private String symbol;
    private String action; // BUY / SELL (largo) o SHORT / COVER (corto)
    private BigDecimal price;
    private String timeframe;
    private long timestamp;
    private boolean isReal;
    private BigDecimal stopLoss; // solo al abrir (BUY / SHORT): con él se dimensiona la posición
    private BigDecimal takeProfit;
    private String reason; // SIGNAL, STOP o TARGET

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

    @JsonAlias("is_real")
    public void setReal(boolean isReal) {
        this.isReal = isReal;
    }

    public BigDecimal getStopLoss() {
        return stopLoss;
    }

    @JsonAlias("stop_loss")
    public void setStopLoss(BigDecimal stopLoss) {
        this.stopLoss = stopLoss;
    }

    public BigDecimal getTakeProfit() {
        return takeProfit;
    }

    @JsonAlias("take_profit")
    public void setTakeProfit(BigDecimal takeProfit) {
        this.takeProfit = takeProfit;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getTimeframe() {
        return timeframe;
    }

    public void setTimeframe(String timeframe) {
        this.timeframe = timeframe;
    }

    /**
     * Clave de idempotencia determinista de la señal.
     *
     * <p>Identifica de forma única una señal por símbolo, timeframe, acción y
     * timestamp de la vela. Dentro de una misma vela una estrategia emite como
     * mucho un BUY o un SELL por símbolo, por lo que una reentrega o un reintento
     * de la misma señal produce la misma clave y puede deduplicarse.
     */
    public String idempotencyKey() {
        return (symbol == null ? "" : symbol) + "|"
                + (timeframe == null ? "" : timeframe) + "|"
                + (action == null ? "" : action) + "|"
                + timestamp;
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
