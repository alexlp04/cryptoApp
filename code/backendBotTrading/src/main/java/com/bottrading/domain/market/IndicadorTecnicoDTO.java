package com.bottrading.domain.market;

import java.math.BigDecimal;

public class IndicadorTecnicoDTO {

    private Long id; // Referencia a la vela

    private String tipo; // Ej: "SMA", "EMA", "RSI", "MACD", "MACD_signal"

    private String parametros; // Ej: "periodo=14", "fast=12,slow=26,signal=9"

    private BigDecimal valor;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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