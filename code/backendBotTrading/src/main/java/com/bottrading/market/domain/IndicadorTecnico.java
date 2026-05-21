package com.bottrading.market.domain;


import com.bottrading.shared.domain.BaseEntity;import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "indicador_tecnico")
public class IndicadorTecnico extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vela_id", nullable = false)
    private Vela vela;

    @Column(name = "tipo", length = 50, nullable = false)
    private String tipo; // Ej: "SMA", "EMA", "RSI", "MACD"

    @Column(name = "parametros", length = 100)
    private String parametros; // Ej: "periodo=14", "fast=12,slow=26,signal=9"

    @Column(name = "valor", precision = 20, scale = 8, nullable = false)
    private BigDecimal valor;

    public Vela getVela() {
        return vela;
    }

    public void setVela(Vela vela) {
        this.vela = vela;
    }

    public String getTipo() {
        return tipo;
    }

    public void setTipo(String tipo) {
        this.tipo = tipo;
    }

    public String getParametros() {
        return parametros;
    }

    public void setParametros(String parametros) {
        this.parametros = parametros;
    }

    public BigDecimal getValor() {
        return valor;
    }

    public void setValor(BigDecimal valor) {
        this.valor = valor;
    }
}