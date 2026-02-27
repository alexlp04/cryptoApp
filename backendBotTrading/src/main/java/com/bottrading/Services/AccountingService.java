package com.bottrading.services;

import java.math.BigDecimal;
import java.time.Instant;

import com.bottrading.beans.InstanciaEstrategia;
import com.bottrading.beans.LedgerEntry;
import com.bottrading.beans.LedgerType;
import com.bottrading.beans.Wallet;
import com.bottrading.repositories.InstanciaEstrategiaRepository;
import com.bottrading.repositories.LedgerRepository;
import com.bottrading.repositories.WalletRepository;

import org.springframework.beans.factory.annotation.Autowired;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Servicio transaccional encargado de todos los movimientos monetarios y contables.
 * Gestiona el Ledger (libro mayor), balances de Wallets y estados financieros de las estrategias.
 */
@Service
@Slf4j
@Transactional
public class AccountingService {

    private final WalletRepository walletRepo;

    private final InstanciaEstrategiaRepository estrategiaRepo;

    private final LedgerRepository ledgerRepo;

    @Autowired
    public AccountingService(WalletRepository walletRepo, InstanciaEstrategiaRepository estrategiaRepo,
            LedgerRepository ledgerRepo) {
        this.walletRepo = walletRepo;
        this.estrategiaRepo = estrategiaRepo;
        this.ledgerRepo = ledgerRepo;
    }

    /**
     * Activa una estrategia reservando el capital especificado de la Wallet.
     * Mueve el saldo de 'Disponible' en la Wallet a 'Capital Asignado' en la Estrategia.
     *
     * @param walletId ID de la billetera origen.
     * @param estrategiaId ID de la estrategia a activar.
     * @param capital Cantidad a reservar.
     * @return La instancia de la estrategia actualizada con estado ACTIVA.
     */
    public InstanciaEstrategia activateStrategy(long walletId, long estrategiaId, BigDecimal capital) {
        try {
            // Bloqueo pesimista para evitar condiciones de carrera en el saldo
            Wallet w = walletRepo.findByIdWithLock(walletId)
                    .orElseThrow(() -> new RuntimeException("Wallet no encontrada"));
            InstanciaEstrategia e = estrategiaRepo.findById(estrategiaId)
                    .orElseThrow(() -> new RuntimeException("Estrategia no encontrada"));

            if (w.getBalanceDisponible().compareTo(capital) < 0) {
                throw new RuntimeException("Fondos insuficientes en balance disponible");
            }

            w.setBalanceDisponible(w.getBalanceDisponible().subtract(capital));
            e.setCapitalAsignado(capital);
            e.setEstado("ACTIVA");

            saveLedger(w, e, LedgerType.RESERVED, capital.negate());

            walletRepo.save(w);
            return estrategiaRepo.save(e);

        } catch (Exception ex) {
            log.error("Error contable al activar estrategia: {}", ex.getMessage(), ex);
            throw ex;
        }
    }

    /**
     * Pausa una estrategia cambiando su estado a DETENIDA.
     * NOTA: No libera el capital reservado.
     *
     * @param walletId ID de la wallet asociada.
     * @param estrategiaId ID de la estrategia.
     */
    public void pauseStrategy(Long walletId, Long estrategiaId) {
        InstanciaEstrategia e = estrategiaRepo.findByIdWithLock(estrategiaId).orElseThrow();
        e.setEstado("DETENIDA");
        estrategiaRepo.save(e);
    }

    /**
     * Finaliza una estrategia y devuelve el capital remanente a la Wallet.
     * Cambia el estado a FINALIZADA.
     *
     * @param walletId ID de la wallet destino.
     * @param estrategiaId ID de la estrategia a liquidar.
     */
    public void closeStrategy(Long walletId, Long estrategiaId) {
        Wallet w = walletRepo.findByIdWithLock(walletId).orElseThrow();
        InstanciaEstrategia e = estrategiaRepo.findByIdWithLock(estrategiaId).orElseThrow();
        
        // Devolución de fondos si queda algo reservado
        if (e.getCapitalReservado().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal liberar = e.getCapitalReservado();

            w.setBalanceDisponible(w.getBalanceDisponible().add(liberar));
            
            saveLedger(w, e, LedgerType.AVAILABLE, liberar);
            
            // Reset contadores estrategia
            e.setCapitalReservado(BigDecimal.ZERO);
            e.setCapitalAsignado(BigDecimal.ZERO);
        }

        e.setEstado("FINALIZADA");
        
        walletRepo.save(w);
        estrategiaRepo.save(e);
    }

    /**
     * Mueve capital dentro de la estrategia: de 'Reservado' a 'Comprometido' (Margen).
     * Se llama al abrir una posición (BUY/SHORT).
     *
     * @param estrategiaId ID de la estrategia.
     * @param margin Cantidad a invertir en la operación.
     * @param risk Riesgo calculado para esta operación.
     */
    public void commitCapital(Long estrategiaId, BigDecimal margin, BigDecimal risk) {
        InstanciaEstrategia e = estrategiaRepo.findByIdWithLock(estrategiaId)
                .orElseThrow(() -> new RuntimeException("Instancia no encontrada"));

        if (e.getCapitalReservado().compareTo(margin) < 0) {
            throw new RuntimeException("Capital reservado insuficiente en la estrategia para abrir operación");
        }

        e.setCapitalReservado(e.getCapitalReservado().subtract(margin));
        e.setCapitalComprometido(e.getCapitalComprometido().add(margin));
        e.setRiesgoAbierto(e.getRiesgoAbierto().add(risk));

        estrategiaRepo.save(e);
    }

    /**
     * Cierra una operación, libera el margen y aplica el PnL (Ganancia/Pérdida).
     * El resultado se suma al capital reservado (Interés compuesto) y al balance real de la Wallet.
     *
     * @param walletId ID de la wallet.
     * @param estrategiaId ID de la estrategia.
     * @param margin Margen liberado.
     * @param pnl Beneficio o pérdida neta.
     * @param risk Riesgo a reducir.
     */
    public void closeTrade(Long walletId, Long estrategiaId, BigDecimal margin, BigDecimal pnl, BigDecimal risk) {
        Wallet w = walletRepo.findByIdWithLock(walletId).orElseThrow();
        InstanciaEstrategia e = estrategiaRepo.findByIdWithLock(estrategiaId).orElseThrow();

        // 1. Actualizar saldos internos de la estrategia
        e.setCapitalComprometido(e.getCapitalComprometido().subtract(margin));
        // El margen vuelve al reservado + la ganancia (o - la pérdida)
        e.setCapitalReservado(e.getCapitalReservado().add(margin).add(pnl));
        e.setRiesgoAbierto(e.getRiesgoAbierto().subtract(risk));

        // 2. Actualizar patrimonio real de la wallet
        w.setBalanceReal(w.getBalanceReal().add(pnl));

        saveLedger(w, e, LedgerType.REALIZED_PNL, pnl);

        walletRepo.save(w);
        estrategiaRepo.save(e);
    }

    /**
     * Aplica una comisión o fee directamente al balance de la wallet.
     */
    public void applyFee(Long walletId, BigDecimal fee) {
        Wallet w = walletRepo.findByIdWithLock(walletId)
                .orElseThrow(() -> new RuntimeException("Wallet no encontrada"));

        w.setBalanceReal(w.getBalanceReal().subtract(fee));

        saveLedger(w, null, LedgerType.FEE, fee.negate());
        walletRepo.save(w);
    }

    // =========================
    // MÉTODOS PRIVADOS
    // =========================

    private void saveLedger(Wallet w, InstanciaEstrategia e, LedgerType type, BigDecimal amount) {
        LedgerEntry le = new LedgerEntry();
        le.setWalletId(w.getId());
        le.setEstrategiaId(e != null ? e.getId() : null);
        le.setType(type);
        le.setAmount(amount);
        le.setBalanceAfter(w.getBalanceReal());
        le.setTimestamp(Instant.now());
        ledgerRepo.save(le);
    }
}