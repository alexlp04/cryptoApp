package com.bottrading.domain.wallet;

import com.bottrading.domain.BaseEntity;
import com.bottrading.domain.user.Usuario;
import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "wallet")
public class Wallet extends BaseEntity {

    @Column(nullable = false)
    private String nombre;

    @ManyToOne(optional = false)
    private Usuario usuario;

    @Column(nullable = false)
    private BigDecimal balanceReal; // dinero real en broker

    @Column(nullable = false)
    private BigDecimal balanceDisponible; // AVAILABLE

    @Enumerated(EnumType.STRING)
    private WalletType type;

    private boolean isActive;

    // ===== getters/setters =====

    public BigDecimal getBalanceReal() {
        return balanceReal;
    }

    public void setBalanceReal(BigDecimal balanceReal) {
        this.balanceReal = balanceReal;
    }

    public BigDecimal getBalanceDisponible() {
        return balanceDisponible;
    }

    public void setBalanceDisponible(BigDecimal balanceDisponible) {
        this.balanceDisponible = balanceDisponible;
    }

    public Usuario getUsuario() {
        return usuario;
    }

    public void setUsuario(Usuario usuario) {
        this.usuario = usuario;
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

    public void setActive(boolean active) {
        isActive = active;
    }

    public String getNombre() {
        return nombre;
    }

    public void setNombre(String nombre) {
        this.nombre = nombre;
    }

}
