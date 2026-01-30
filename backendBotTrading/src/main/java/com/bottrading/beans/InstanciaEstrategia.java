package com.bottrading.beans;

import java.math.BigDecimal;
import java.util.List;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

@Entity
@Table(name = "instancia_estrategia")
public class InstanciaEstrategia extends BaseEntity {

    @Column(name = "nombre_estrategia")
    private String nombreEstrategia;

    @Column(name = "wallet_asociada")
    private Long walletAsociada;

    private String timeframe;

    // Base de riesgo (NO cambia durante la estrategia)
    @Column(name = "capital_asignado")
    private BigDecimal capitalAsignado;

    @Column(name = "risk_per_trade")
    private BigDecimal riskPerTrade;

    // Estados dinámicos

    @Column(name = "capital_reservado")
    private BigDecimal capitalReservado; // RESERVED
    
    @Column(name = "capital_comprometido")
    private BigDecimal capitalComprometido; // COMMITTED

    @Column(name = "riesgo_abierto")
    private BigDecimal riesgoAbierto; // riesgo vivo

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "instancia_simbolos", joinColumns = @JoinColumn(name = "instancia_id"))
    @Column(name = "simbolo")                           
    private List<String> simbolos;

    @Column(name = "es_real")
    private boolean esReal;

    @Column(name = "estado")
    private String estado; // ACTIVA, FINALIZADA, SIN_FONDOS

    public InstanciaEstrategia() {
        super();
    }

    public String getNombreEstrategia() {
        return nombreEstrategia;
    }

    public void setNombreEstrategia(String nombreEstrategia) {
        this.nombreEstrategia = nombreEstrategia;
    }

    public Long getWalletAsociada() {
        return walletAsociada;
    }

    public void setWalletAsociada(Long walletAsociada) {
        this.walletAsociada = walletAsociada;
    }

    public String getTimeframe() {
        return timeframe;
    }

    public void setTimeframe(String timeframe) {
        this.timeframe = timeframe;
    }

    public BigDecimal getCapitalAsignado() {
        return capitalAsignado;
    }

    public void setCapitalAsignado(BigDecimal capitalAsignado) {
        this.capitalAsignado = capitalAsignado;
    }

    public BigDecimal getRiskPerTrade() {
        return riskPerTrade;
    }

    public void setRiskPerTrade(BigDecimal riskPerTrade) {
        this.riskPerTrade = riskPerTrade;
    }

    public BigDecimal getCapitalReservado() {
        return capitalReservado;
    }

    public void setCapitalReservado(BigDecimal capitalReservado) {
        this.capitalReservado = capitalReservado;
    }

    public BigDecimal getCapitalComprometido() {
        return capitalComprometido;
    }

    public void setCapitalComprometido(BigDecimal capitalComprometido) {
        this.capitalComprometido = capitalComprometido;
    }

    public BigDecimal getRiesgoAbierto() {
        return riesgoAbierto;
    }

    public void setRiesgoAbierto(BigDecimal riesgoAbierto) {
        this.riesgoAbierto = riesgoAbierto;
    }

    public List<String> getSimbolos() {
        return simbolos;
    }

    public void setSimbolos(List<String> simbolos) {
        this.simbolos = simbolos;
    }

    public boolean isEsReal() {
        return esReal;
    }

    public void setEsReal(boolean esReal) {
        this.esReal = esReal;
    }

    public String getEstado() {
        return estado;
    }

    public void setEstado(String estado) {
        this.estado = estado;
    }

}
