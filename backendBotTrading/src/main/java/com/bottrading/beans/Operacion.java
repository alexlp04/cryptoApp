package com.bottrading.beans;

import java.time.LocalDateTime;

public class Operacion {
    private String simbolo;
    private String tipo; // "BUY" o "SELL"
    private double cantidad;
    private double precioEntrada;
    private Double precioSalida; // null si no ha cerrado
    private LocalDateTime fechaEntrada;
    private LocalDateTime fechaSalida;
    private String estrategia;
    private Double beneficio; // null si aún está abierta
    private Usuario usuario;

    // Constructores
    public Operacion(String simbolo, String tipo, double cantidad, double precioEntrada,
                     LocalDateTime fechaEntrada, String estrategia, Usuario usuario) {
        this.simbolo = simbolo;
        this.tipo = tipo;
        this.cantidad = cantidad;
        this.precioEntrada = precioEntrada;
        this.fechaEntrada = fechaEntrada;
        this.usuario = usuario;
        this.estrategia = estrategia;
    }

    public String getSimbolo() {
        return simbolo;
    }

    public void setSimbolo(String simbolo) {
        this.simbolo = simbolo;
    }

    public String getTipo() {
        return tipo;
    }

    public void setTipo(String tipo) {
        this.tipo = tipo;
    }

    public double getCantidad() {
        return cantidad;
    }

    public void setCantidad(double cantidad) {
        this.cantidad = cantidad;
    }

    public double getPrecioEntrada() {
        return precioEntrada;
    }

    public void setPrecioEntrada(double precioEntrada) {
        this.precioEntrada = precioEntrada;
    }

    public Double getPrecioSalida() {
        return precioSalida;
    }

    public void setPrecioSalida(Double precioSalida) {
        this.precioSalida = precioSalida;
    }

    public LocalDateTime getFechaEntrada() {
        return fechaEntrada;
    }

    public void setFechaEntrada(LocalDateTime fechaEntrada) {
        this.fechaEntrada = fechaEntrada;
    }

    public LocalDateTime getFechaSalida() {
        return fechaSalida;
    }

    public void setFechaSalida(LocalDateTime fechaSalida) {
        this.fechaSalida = fechaSalida;
    }

    public String getEstrategia() {
        return estrategia;
    }

    public void setEstrategia(String estrategia) {
        this.estrategia = estrategia;
    }

    public Double getBeneficio() {
        return beneficio;
    }

    public void setBeneficio(Double beneficio) {
        this.beneficio = beneficio;
    }

    public Usuario getUsuario() {
        return usuario;
    }

    public void setUsuario(Usuario usuario) {
        this.usuario = usuario;
    }
    

}
