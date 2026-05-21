package com.bottrading.trading.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bottrading.shared.exceptions.ValidationException;
import com.bottrading.strategy.domain.EstadoEstrategia;
import com.bottrading.strategy.domain.InstanciaEstrategia;
import com.bottrading.strategy.domain.InstanciaEstrategiaRepository;
import com.bottrading.trading.application.port.in.AccountingUseCase;
import com.bottrading.trading.application.port.out.LedgerRepositoryPort;
import com.bottrading.trading.domain.LedgerEntry;
import com.bottrading.trading.domain.LedgerType;
import com.bottrading.wallet.application.port.out.WalletRepositoryPort;
import com.bottrading.wallet.domain.Wallet;

import lombok.extern.slf4j.Slf4j;

/**
 * Servicio transaccional encargado de todos los movimientos monetarios y contables.
 * Gestiona el Ledger (libro mayor), balances de Wallets y estados financieros de las estrategias.
 */
@Service
@Slf4j
@Transactional
public class AccountingService implements AccountingUseCase {

    private final WalletRepositoryPort walletRepo;

    private final InstanciaEstrategiaRepository estrategiaRepo;

    private final LedgerRepositoryPort ledgerRepo;

    @Autowired
    public AccountingService(WalletRepositoryPort walletRepo, InstanciaEstrategiaRepository estrategiaRepo,
            LedgerRepositoryPort ledgerRepo) {
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
    @Override
    public InstanciaEstrategia activateStrategy(long walletId, long estrategiaId, BigDecimal capital) {
        try {
            // Bloqueo pesimista en ambas entidades para evitar condiciones de carrera
            Wallet w = walletRepo.findByIdWithLock(walletId)
                    .orElseThrow(() -> new ValidationException("Wallet no encontrada: " + walletId));
            InstanciaEstrategia e = estrategiaRepo.findByIdWithLock(estrategiaId)
                    .orElseThrow(() -> new ValidationException("Estrategia no encontrada: " + estrategiaId));

            if (w.getBalanceDisponible().compareTo(capital) < 0) {
                throw new ValidationException("Fondos insuficientes en balance disponible");
            }

            w.setBalanceDisponible(w.getBalanceDisponible().subtract(capital));
            e.setCapitalAsignado(capital);
            e.setEstado(EstadoEstrategia.ACTIVA);

            saveLedger(w, e, LedgerType.RESERVED, capital.negate());

            walletRepo.save(w);
            return estrategiaRepo.save(e);

        } catch (Exception ex) {
            log.error("Error contable al activar estrategia: {}", ex.getMessage(), ex);
            throw ex;
        }
    }

    /**
     * Pausa temporalmente una estrategia SIN liberar el capital reservado.
     * El capital permanece congelado pero la estrategia puede reanudarse.
     * 
     * NOTA: El capital permanece bloqueado. Si necesitas recuperarlo, usa closeStrategy().
     *
     * @param walletId ID de la wallet asociada.
     * @param estrategiaId ID de la estrategia.
     */
    @Override
    public void pauseStrategyTemporarily(Long walletId, Long estrategiaId) {
        InstanciaEstrategia e = estrategiaRepo.findByIdWithLock(estrategiaId)
                .orElseThrow(() -> new ValidationException("Estrategia no encontrada: " + estrategiaId));
        e.setEstado(EstadoEstrategia.DETENIDA);
        
        // Registrar en Ledger la pausa (sin movimiento de dinero, solo meta dato)
        LedgerEntry le = new LedgerEntry();
        le.setWalletId(walletId);
        le.setEstrategiaId(e.getId());
        le.setType(LedgerType.STRATEGY_PAUSED);
        le.setAmount(BigDecimal.ZERO);
        le.setBalanceAfter(e.getCapitalReservado());
        le.setTimestamp(Instant.now());
        ledgerRepo.save(le);
        
        estrategiaRepo.save(e);
        log.info("Estrategia {} pausada temporalmente (capital congelado)", estrategiaId);
    }

    /**
     * Finaliza una estrategia y devuelve el capital remanente a la Wallet.
     * Cambia el estado a FINALIZADA.
     *
     * @param walletId ID de la wallet destino.
     * @param estrategiaId ID de la estrategia a liquidar.
     */
    @Override
    public void closeStrategy(Long walletId, Long estrategiaId) {
        Wallet w = walletRepo.findByIdWithLock(walletId)
                .orElseThrow(() -> new ValidationException("Wallet no encontrada: " + walletId));
        InstanciaEstrategia e = estrategiaRepo.findByIdWithLock(estrategiaId)
                .orElseThrow(() -> new ValidationException("Estrategia no encontrada: " + estrategiaId));
        
        // Devolución de fondos si queda algo reservado
        if (e.getCapitalReservado().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal liberar = e.getCapitalReservado();

            w.setBalanceDisponible(w.getBalanceDisponible().add(liberar));
            
            saveLedger(w, e, LedgerType.AVAILABLE, liberar);
            
            // Reset contadores estrategia
            e.setCapitalReservado(BigDecimal.ZERO);
            e.setCapitalAsignado(BigDecimal.ZERO);
        }

        e.setEstado(EstadoEstrategia.TERMINADA);
        
        walletRepo.save(Objects.requireNonNull(w, "Wallet no puede ser null"));
        estrategiaRepo.save(e);
    }

    /**
     * Mueve capital dentro de la estrategia: de 'Reservado' a 'Comprometido' (Margen).
     * Se llama al abrir una posición (BUY/SHORT).
     * IMPORTANTE: Ahora registra la operación en el Ledger para auditoria completa.
     *
     * @param estrategiaId ID de la estrategia.
     * @param margin Cantidad a invertir en la operación.
     * @param risk Riesgo calculado para esta operación.
     */
    @Override
    public void commitCapital(Long estrategiaId, BigDecimal margin, BigDecimal risk) {
        InstanciaEstrategia e = estrategiaRepo.findByIdWithLock(estrategiaId)
                .orElseThrow(() -> new ValidationException("Estrategia no encontrada: " + estrategiaId));

        if (e.getCapitalReservado().compareTo(margin) < 0) {
            throw new ValidationException("Capital reservado insuficiente en la estrategia para abrir operación");
        }

        e.setCapitalReservado(e.getCapitalReservado().subtract(margin));
        e.setCapitalComprometido(e.getCapitalComprometido().add(margin));
        e.setRiesgoAbierto(e.getRiesgoAbierto().add(risk));

        // 🔥 NUEVO: Registrar en Ledger para auditoria completa
        LedgerEntry le = new LedgerEntry();
        le.setWalletId(e.getWalletAsociada());
        le.setEstrategiaId(e.getId());
        le.setType(LedgerType.MARGIN_COMMITTED);
        le.setAmount(margin.negate());
        le.setBalanceAfter(e.getCapitalReservado());
        le.setTimestamp(Instant.now());
        ledgerRepo.save(le);

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
    @Override
    public void closeTrade(Long walletId, Long estrategiaId, BigDecimal margin, BigDecimal pnl, BigDecimal risk) {
        Wallet w = walletRepo.findByIdWithLock(walletId)
                .orElseThrow(() -> new ValidationException("Wallet no encontrada: " + walletId));
        InstanciaEstrategia e = estrategiaRepo.findByIdWithLock(estrategiaId)
                .orElseThrow(() -> new ValidationException("Estrategia no encontrada: " + estrategiaId));

        e.setCapitalComprometido(e.getCapitalComprometido().subtract(margin));
        // El margen vuelve al reservado + la ganancia (o - la pérdida)
        e.setCapitalReservado(e.getCapitalReservado().add(margin).add(pnl));
        BigDecimal nuevoRiesgo = e.getRiesgoAbierto().subtract(risk);
        e.setRiesgoAbierto(nuevoRiesgo.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : nuevoRiesgo);

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
                .orElseThrow(() -> new ValidationException("Wallet no encontrada: " + walletId));

        w.setBalanceReal(w.getBalanceReal().subtract(fee));

        saveLedger(w, null, LedgerType.FEE, fee.negate());
        walletRepo.save(w);
    }

    // MÉTODOS PRIVADOS

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