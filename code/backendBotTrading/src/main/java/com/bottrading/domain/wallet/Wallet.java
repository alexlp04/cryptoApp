package com.bottrading.domain.wallet;

import com.bottrading.domain.BaseEntity;
import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

@Entity
@Table(name = "wallet")
public class Wallet extends BaseEntity {

    @Column(nullable = false)
    private String nombre;

    @Column(name = "usuario_id", nullable = false)
    private Long usuarioId;

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
