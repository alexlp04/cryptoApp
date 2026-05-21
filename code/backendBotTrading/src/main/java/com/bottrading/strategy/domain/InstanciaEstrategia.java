package com.bottrading.strategy.domain;


import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.bottrading.shared.domain.BaseEntity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

@Entity
@Table(name = "instancia_estrategia")
public class InstanciaEstrategia extends BaseEntity {

    @Column(name = "nombre_estrategia")
    private String nombreEstrategia;

    @Column(name = "nombre_modelo", length = 100)
    private String nombreModelo;

    @Column(name = "wallet_asociada")
    private Long walletAsociada;

    private String timeframe;

    // Base de riesgo (NO cambia durante la estrategia)
    @Column(name = "capital_asignado")
    private BigDecimal capitalAsignado;

    @Column(name = "risk_per_trade")
    private BigDecimal riskPerTrade;

    @Column(name = "capital_reservado")
    private BigDecimal capitalReservado; // RESERVED

    // Estados dinámicos con VALORES POR DEFECTO

    @Column(name = "capital_comprometido", precision = 18, scale = 8)
    private BigDecimal capitalComprometido = BigDecimal.ZERO;

    @Column(name = "riesgo_abierto", precision = 18, scale = 8)
    private BigDecimal riesgoAbierto = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private EstadoEstrategia estado = EstadoEstrategia.CREADA;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "instancia_simbolos", joinColumns = @JoinColumn(name = "instancia_id"))
    @Column(name = "simbolo")
    private List<String> simbolos;

    @Column(name = "es_real")
    private boolean esReal;


    public InstanciaEstrategia() {
        super();
    }

    /**
     * Crea una nueva instancia lista para ser ejecutada, aplicando la lógica de negocio básica.
     */
    public static InstanciaEstrategia inicializar(String nombreEstra, String nombreModelo, String tf,
            List<String> coins, boolean isReal, Long walletId,
            BigDecimal risk, BigDecimal capital) {

        InstanciaEstrategia instancia = new InstanciaEstrategia();

        instancia.setNombreEstrategia(nombreEstra);
        instancia.setNombreModelo(nombreModelo);
        instancia.setTimeframe(tf);
        instancia.setCapitalAsignado(capital);
        instancia.setCapitalReservado(capital);
        instancia.setRiskPerTrade(risk);
        instancia.setSimbolos(new ArrayList<>(coins));
        instancia.setEsReal(isReal);
        instancia.setWalletAsociada(walletId);

        return instancia;
    }


    public String getNombreEstrategia() {
        return nombreEstrategia;
    }

    public void setNombreEstrategia(String nombreEstrategia) {
        this.nombreEstrategia = nombreEstrategia;
    }

    public String getNombreModelo() {
        return nombreModelo;
    }

    public void setNombreModelo(String nombreModelo) {
        this.nombreModelo = nombreModelo;
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

    public EstadoEstrategia getEstado() {
        return estado;
    }

    public void setEstado(EstadoEstrategia estado) {
        this.estado = estado;
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

    @Override
    public String toString() {
        return String.format("ID: %d | Estrategia: %s | Modelo: %s | Symbols: %s | Capital: %s | Estado: %s",
                this.getId(),
                this.getNombreEstrategia(),
                this.getNombreModelo() != null ? this.getNombreModelo() : "N/A",
                this.getSimbolos(),
                this.getCapitalReservado() != null ? this.getCapitalReservado().toPlainString() : "0.00",
                this.getEstado());
    }
}