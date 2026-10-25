package com.bottrading.wallet.domain;

import com.bottrading.shared.domain.BaseEntity;
import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "wallet", uniqueConstraints = @UniqueConstraint(name = "uk_wallet_usuario_nombre", columnNames = {"usuario_id", "nombre"}))
public class Wallet extends BaseEntity {

    @Column(nullable = false)
    private String nombre;

    @Column(name = "usuario_id", nullable = false)
    private Long usuarioId;

    @Column(nullable = false, precision = 18, scale = 8)
    private BigDecimal balanceReal; // dinero real en broker

    @Column(nullable = false, precision = 18, scale = 8)
    private BigDecimal balanceDisponible; // AVAILABLE

    @Enumerated(EnumType.STRING)
    private WalletType type;

    private boolean isActive;


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

    public Long getUsuarioId() {
        return usuarioId;
    }

    public void setUsuarioId(Long usuarioId) {
        this.usuarioId = usuarioId;
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
