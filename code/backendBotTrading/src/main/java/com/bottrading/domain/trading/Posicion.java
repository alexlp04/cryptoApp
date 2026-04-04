package com.bottrading.domain.trading;

import java.math.BigDecimal;
import java.time.Instant;

import com.bottrading.domain.BaseEntity;
import com.bottrading.domain.strategy.InstanciaEstrategia;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "posicion")
public class Posicion extends BaseEntity {
    private String simbolo;
    private BigDecimal precioEntrada;
    private BigDecimal margenInvertido; // USD exactos
    private boolean abierta = true;

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

}