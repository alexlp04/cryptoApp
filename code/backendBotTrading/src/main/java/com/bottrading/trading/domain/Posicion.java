package com.bottrading.trading.domain;

import java.math.BigDecimal;
import java.time.Instant;

import com.bottrading.shared.domain.BaseEntity;
import com.bottrading.strategy.domain.InstanciaEstrategia;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "posicion")
public class Posicion extends BaseEntity {
    private String simbolo;
    @Column(precision = 18, scale = 8)
    private BigDecimal precioEntrada;
    @Column(precision = 18, scale = 8)
    private BigDecimal margenInvertido; // USD exactos
    private boolean abierta = true;

    @Enumerated(EnumType.STRING)
    @Column(length = 5, nullable = false)
    private Lado lado = Lado.LONG;

    /** Unidades operadas (null en posiciones anteriores a la gestión de riesgo). */
    @Column(precision = 24, scale = 8)
    private BigDecimal unidades;

    @Column(name = "stop_loss", precision = 20, scale = 8)
    private BigDecimal stopLoss;

    @Column(name = "take_profit", precision = 20, scale = 8)
    private BigDecimal takeProfit;

    /** Dinero que se pierde si salta el stop: lo que cuenta como riesgo abierto de la estrategia. */
    @Column(precision = 18, scale = 8)
    private BigDecimal riesgo;

    @Column(precision = 18, scale = 8)
    private BigDecimal comisiones;

    @Column(precision = 20, scale = 8)
    private BigDecimal precioSalida;

    @Column(precision = 20, scale = 8)
    private BigDecimal pnl;

    @Column(name = "fecha_cierre")
    private Instant fechaCierre;

    @ManyToOne
    private InstanciaEstrategia instancia;

    public String getSimbolo() {
        return simbolo;
    }

    public void setSimbolo(String simbolo) {
        this.simbolo = simbolo;
    }

    public BigDecimal getPrecioEntrada() {
        return precioEntrada;
    }

    public void setPrecioEntrada(BigDecimal precioEntrada) {
        this.precioEntrada = precioEntrada;
    }

    public BigDecimal getMargenInvertido() {
        return margenInvertido;
    }

    public void setMargenInvertido(BigDecimal margenInvertido) {
        this.margenInvertido = margenInvertido;
    }

    public Lado getLado() {
        return lado;
    }

    public void setLado(Lado lado) {
        this.lado = lado;
    }

    public boolean isAbierta() {
        return abierta;
    }

    public void setAbierta(boolean abierta) {
        this.abierta = abierta;
    }

    public InstanciaEstrategia getInstancia() {
        return instancia;
    }

    public void setInstancia(InstanciaEstrategia instancia) {
        this.instancia = instancia;
    }

    public BigDecimal getPrecioSalida() {
        return precioSalida;
    }

    public void setPrecioSalida(BigDecimal precioSalida) {
        this.precioSalida = precioSalida;
    }

    public BigDecimal getPnl() {
        return pnl;
    }

    public void setPnl(BigDecimal pnl) {
        this.pnl = pnl;
    }

    public Instant getFechaCierre() {
        return fechaCierre;
    }

    public void setFechaCierre(Instant fechaCierre) {
        this.fechaCierre = fechaCierre;
    }

    public BigDecimal getUnidades() {
        return unidades;
    }

    public void setUnidades(BigDecimal unidades) {
        this.unidades = unidades;
    }

    public BigDecimal getStopLoss() {
        return stopLoss;
    }

    public void setStopLoss(BigDecimal stopLoss) {
        this.stopLoss = stopLoss;
    }

    public BigDecimal getTakeProfit() {
        return takeProfit;
    }

    public void setTakeProfit(BigDecimal takeProfit) {
        this.takeProfit = takeProfit;
    }

    public BigDecimal getRiesgo() {
        return riesgo;
    }

    public void setRiesgo(BigDecimal riesgo) {
        this.riesgo = riesgo;
    }

    public BigDecimal getComisiones() {
        return comisiones;
    }

    public void setComisiones(BigDecimal comisiones) {
        this.comisiones = comisiones;
    }

}
