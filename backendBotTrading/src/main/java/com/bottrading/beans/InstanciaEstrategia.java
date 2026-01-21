package com.bottrading.beans;

import java.util.List;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

@Entity
@Table(name = "instancias_estrategia")
public class InstanciaEstrategia {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "nombre_estrategia")
    private String nombreEstrategia;

    @Column(name = "wallet_asociada")
    private String walletAsociada;

    private String timeframe;

    @Column(name = "capital_asignado_actual")
    private double capitalAsignadoActual;

    @Column(name = "risk_per_trade")
    private double riskPerTrade;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "instancia_simbolos", joinColumns = @JoinColumn(name = "instancia_id"))
    @Column(name = "simbolo")
    private List<String> simbolos;

    private boolean esReal;

    private String estado; // ACTIVA, FINALIZADA, SIN_FONDOS

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getNombreEstrategia() {
        return nombreEstrategia;
    }

    public void setNombreEstrategia(String nombreEstrategia) {
        this.nombreEstrategia = nombreEstrategia;
    }

    public String getWalletAsociada() {
        return walletAsociada;
    }

    public void setWalletAsociada(String walletAsociada) {
        this.walletAsociada = walletAsociada;
    }

    public String getTimeframe() {
        return timeframe;
    }

    public void setTimeframe(String timeframe) {
        this.timeframe = timeframe;
    }

    public double getCapitalAsignadoActual() {
        return capitalAsignadoActual;
    }

    public void setCapitalAsignadoActual(double capitalAsignadoActual) {
        this.capitalAsignadoActual = capitalAsignadoActual;
    }

    public double getRiskPerTrade() {
        return riskPerTrade;
    }

    public void setRiskPerTrade(double riskPerTrade) {
        this.riskPerTrade = riskPerTrade;
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

    public boolean estaActiva() {
        return estado.equals("ACTIVA");
    }

    

    
}