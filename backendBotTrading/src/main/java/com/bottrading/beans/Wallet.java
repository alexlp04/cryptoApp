package com.bottrading.beans;

import jakarta.persistence.*;
import java.util.concurrent.locks.ReentrantLock;

@Entity
@Table(name = "wallet")
public class Wallet extends BaseEntity {

    @ManyToOne(optional = false)
    private Usuario usuario;

    @Column(nullable = false)
    private double balance;

    @Column(nullable = false)
    private double balanceDisponible; // balance - órdenes pendientes

    @Enumerated(EnumType.STRING)
    private WalletType type;

    private boolean isActive;

    // Lock transient (no se persiste en BD)
    @Transient
    private final ReentrantLock lock = new ReentrantLock();

    // Métodos synchronized para operaciones críticas
    public synchronized boolean reservarCapital(double cantidad) {
        if (balanceDisponible >= cantidad) {
            balanceDisponible -= cantidad;
            return true;
        }
        return false;
    }

    public synchronized void liberarCapital(double cantidad) {
        balanceDisponible += cantidad;
    }

    public synchronized boolean ejecutarCompra(double cantidad) {
        if (balance >= cantidad) {
            balance -= cantidad;
            balanceDisponible -= cantidad;
            return true;
        }
        return false;
    }

    public synchronized void ejecutarVenta(double cantidad) {
        balance += cantidad;
        balanceDisponible += cantidad;
    }

    public synchronized boolean canRisk(double riskAmount) {
        return balanceDisponible >= riskAmount;
    }

    public synchronized void adjustBalance(double amount) {
        this.balance += amount;
        this.balanceDisponible += amount;
    }

    // Getters y setters
    public Usuario getUsuario() {
        return usuario;
    }

    public void setUsuario(Usuario usuario) {
        this.usuario = usuario;
    }

    public double getBalance() {
        return balance;
    }

    public void setBalance(double balance) {
        this.balance = balance;
        // Inicializar balanceDisponible si es nuevo
        if (this.balanceDisponible == 0) {
            this.balanceDisponible = balance;
        }
    }

    public double getBalanceDisponible() {
        return balanceDisponible;
    }

    public WalletType getType() {
        return type;
    }

    public void setType(WalletType type) {
        this.type = type;
    }

    public boolean isActive() {
        return isActive;
    }

    public void setActive(boolean isActive) {
        this.isActive = isActive;
    }

    public boolean isPaper() {
        return this.type == WalletType.PAPER;
    }

    public boolean isReal() {
        return this.type == WalletType.REAL;
    }

    @Override
    public String toString() {
        return String.format("%s | %.2f USD (%s) %s", 
            getNombre(), 
            balance, 
            type, 
            isActive ? "[ACTIVA]" : "");
    }
}