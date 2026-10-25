package com.bottrading.trading.application;

import java.math.BigDecimal;
import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bottrading.shared.exceptions.ValidationException;
import com.bottrading.strategy.domain.EstadoEstrategia;
import com.bottrading.strategy.domain.InstanciaEstrategia;
import com.bottrading.strategy.domain.InstanciaEstrategiaRepository;
import com.bottrading.trading.application.port.in.AccountingUseCase;
import com.bottrading.trading.application.port.out.LedgerRepositoryPort;
import com.bottrading.trading.application.port.out.PosicionRepositoryPort;
import com.bottrading.trading.domain.Posicion;
import com.bottrading.trading.domain.LedgerEntry;
import com.bottrading.trading.domain.LedgerType;
import com.bottrading.wallet.application.port.out.WalletRepositoryPort;
import com.bottrading.wallet.domain.Wallet;
import com.bottrading.wallet.domain.WalletType;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Servicio transaccional encargado de todos los movimientos monetarios y contables.
 * Gestiona el Ledger (libro mayor), balances de Wallets y estados financieros de las estrategias.
 */
@Service
@Slf4j
@Transactional
@RequiredArgsConstructor
public class AccountingService implements AccountingUseCase {

    private final WalletRepositoryPort walletRepo;

    private final InstanciaEstrategiaRepository estrategiaRepo;

    private final LedgerRepositoryPort ledgerRepo;

    private final PosicionRepositoryPort posicionRepo;

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
            InstanciaEstrategia e = estrategiaRepo.findByIdWithLock(estrategiaId)
                    .orElseThrow(() -> new ValidationException("Estrategia no encontrada: " + estrategiaId));
            Wallet w = walletRepo.findByIdWithLock(walletId)
                    .orElseThrow(() -> new ValidationException("Wallet no encontrada: " + walletId));

            validarActivacion(w, e, capital);

            if (w.getBalanceDisponible().compareTo(capital) < 0) {
                throw new ValidationException("Fondos insuficientes en balance disponible");
            }

            w.setBalanceDisponible(w.getBalanceDisponible().subtract(capital));
            e.setCapitalAsignado(capital);
            e.setEstado(EstadoEstrategia.ACTIVA);

            saveLedger(w, e, LedgerType.RESERVED, capital.negate(), "estrategia:" + estrategiaId + ":activar");

            walletRepo.save(w);
            return estrategiaRepo.save(e);

        } catch (Exception ex) {
            log.error("Error contable al activar estrategia: {}", ex.getMessage(), ex);
            throw ex;
        }
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
        InstanciaEstrategia e = estrategiaRepo.findByIdWithLock(estrategiaId)
                .orElseThrow(() -> new ValidationException("Estrategia no encontrada: " + estrategiaId));
        Wallet w = walletRepo.findByIdWithLock(walletId)
                .orElseThrow(() -> new ValidationException("Wallet no encontrada: " + walletId));
        
        liquidarPosicionesAbiertas(w, e);

        // Devolución de fondos si queda algo reservado
        if (e.getCapitalReservado().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal liberar = e.getCapitalReservado();

            w.setBalanceDisponible(w.getBalanceDisponible().add(liberar));
            
            saveLedger(w, e, LedgerType.AVAILABLE, liberar, "estrategia:" + estrategiaId + ":cerrar");
            
            // Reset contadores estrategia
            e.setCapitalReservado(BigDecimal.ZERO);
            e.setCapitalAsignado(BigDecimal.ZERO);
        }

        e.setEstado(EstadoEstrategia.TERMINADA);
        
        walletRepo.save(w);
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
    public void commitCapital(Long estrategiaId, BigDecimal margin, BigDecimal risk, String reference) {
        InstanciaEstrategia e = estrategiaRepo.findByIdWithLock(estrategiaId)
                .orElseThrow(() -> new ValidationException("Estrategia no encontrada: " + estrategiaId));
        Wallet w = walletRepo.findByIdWithLock(e.getWalletAsociada())
                .orElseThrow(() -> new ValidationException("Wallet no encontrada: " + e.getWalletAsociada()));

        exigirPositivo(margin, "margen");
        if (risk == null || risk.signum() < 0) {
            throw new ValidationException("El riesgo no puede ser negativo ni nulo");
        }
        if (e.getCapitalReservado().compareTo(margin) < 0) {
            throw new ValidationException("Capital reservado insuficiente en la estrategia para abrir operación");
        }

        e.setCapitalReservado(e.getCapitalReservado().subtract(margin));
        e.setCapitalComprometido(e.getCapitalComprometido().add(margin));
        e.setRiesgoAbierto(e.getRiesgoAbierto().add(risk));

        saveLedger(w, e, LedgerType.MARGIN_COMMITTED, margin.negate(), reference);

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
    public void closeTrade(Long walletId, Long estrategiaId, BigDecimal margin, BigDecimal pnl, BigDecimal risk,
            String reference) {
        InstanciaEstrategia e = estrategiaRepo.findByIdWithLock(estrategiaId)
                .orElseThrow(() -> new ValidationException("Estrategia no encontrada: " + estrategiaId));
        Wallet w = walletRepo.findByIdWithLock(walletId)
                .orElseThrow(() -> new ValidationException("Wallet no encontrada: " + walletId));

        exigirPositivo(margin, "margen");
        if (pnl == null || risk == null || risk.signum() < 0) {
            throw new ValidationException("PnL y riesgo son obligatorios y el riesgo no puede ser negativo");
        }

        e.setCapitalComprometido(e.getCapitalComprometido().subtract(margin));
        // El margen vuelve al reservado + la ganancia (o - la pérdida)
        e.setCapitalReservado(e.getCapitalReservado().add(margin).add(pnl));
        BigDecimal nuevoRiesgo = e.getRiesgoAbierto().subtract(risk);
        e.setRiesgoAbierto(nuevoRiesgo.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : nuevoRiesgo);

        w.setBalanceReal(w.getBalanceReal().add(pnl));

        saveLedger(w, e, LedgerType.REALIZED_PNL, pnl, reference);

        walletRepo.save(w);
        estrategiaRepo.save(e);
    }

    /**
     * Aplica una comisión directamente a la wallet. Sale de balanceReal y de balanceDisponible a la
     * vez para mantener balanceReal == disponible + capital repartido en estrategias.
     */
    public void applyFee(Long walletId, BigDecimal fee) {
        exigirPositivo(fee, "comisión");
        Wallet w = walletRepo.findByIdWithLock(walletId)
                .orElseThrow(() -> new ValidationException("Wallet no encontrada: " + walletId));

        w.setBalanceReal(w.getBalanceReal().subtract(fee));
        w.setBalanceDisponible(w.getBalanceDisponible().subtract(fee));

        saveLedger(w, null, LedgerType.FEE, fee.negate(), null);
        walletRepo.save(w);
    }

    /**
     * Desvío del invariante balanceReal == balanceDisponible + capital repartido en estrategias
     * (reservado + comprometido). Debe ser 0; distinto de 0 indica dinero perdido o creado.
     */
    @Transactional(readOnly = true)
    public BigDecimal desvioDelInvariante(Long walletId) {
        Wallet w = walletRepo.findByIdWithLock(walletId)
                .orElseThrow(() -> new ValidationException("Wallet no encontrada: " + walletId));
        BigDecimal repartido = estrategiaRepo.sumCapitalRepartidoByWallet(walletId);
        return w.getBalanceReal().subtract(w.getBalanceDisponible().add(repartido == null ? BigDecimal.ZERO : repartido));
    }

    // MÉTODOS PRIVADOS

    private static void exigirPositivo(BigDecimal valor, String nombre) {
        if (valor == null || valor.signum() <= 0) {
            throw new ValidationException("El valor de " + nombre + " debe ser mayor que cero");
        }
    }

    /** Un capital negativo crearía saldo disponible; un riesgo fuera de (0, 1] deja la estrategia sin operar. */
    private static void validarActivacion(Wallet w, InstanciaEstrategia e, BigDecimal capital) {
        exigirPositivo(capital, "capital");
        BigDecimal risk = e.getRiskPerTrade();
        if (risk != null && (risk.signum() <= 0 || risk.compareTo(BigDecimal.ONE) > 0)) {
            throw new ValidationException("El riesgo por trade debe estar en (0, 1]: " + risk);
        }
        if (w.getType() != null && e.isEsReal() != (w.getType() == WalletType.REAL)) {
            throw new ValidationException("El tipo de wallet (" + w.getType() + ") no coincide con el modo de la estrategia");
        }
    }

    /**
     * Cierra a coste (PnL 0) las posiciones que sigan abiertas y devuelve su margen al reservado.
     * Sin esto, terminar una estrategia (orden del usuario, apagado o caída del motor) dejaba el
     * margen comprometido fuera de todos los saldos y la posición abierta para siempre.
     */
    private void liquidarPosicionesAbiertas(Wallet w, InstanciaEstrategia e) {
        BigDecimal comprometido = e.getCapitalComprometido();
        if (comprometido == null || comprometido.signum() <= 0) {
            return;
        }
        for (Posicion pos : posicionRepo.findByInstanciaAndAbiertaTrue(e)) {
            pos.setAbierta(false);
            pos.setPrecioSalida(pos.getPrecioEntrada());
            pos.setPnl(BigDecimal.ZERO);
            pos.setFechaCierre(Instant.now());
            posicionRepo.save(pos);
        }
        log.warn("Estrategia {} terminada con {} comprometido: posiciones cerradas a coste (PnL 0)",
                e.getId(), comprometido);
        e.setCapitalReservado(e.getCapitalReservado().add(comprometido));
        e.setCapitalComprometido(BigDecimal.ZERO);
        e.setRiesgoAbierto(BigDecimal.ZERO);
        saveLedger(w, e, LedgerType.REALIZED_PNL, BigDecimal.ZERO, "estrategia:" + e.getId() + ":liquidacion");
    }

    /**
     * balanceAfter es siempre el balanceReal de la wallet tras el movimiento (así el ledger se lee con un
     * único significado); reference liga la entrada con lo que la causó, p. ej. "posicion:12:open".
     */
    private void saveLedger(Wallet w, InstanciaEstrategia e, LedgerType type, BigDecimal amount, String reference) {
        LedgerEntry le = new LedgerEntry();
        le.setWalletId(w.getId());
        le.setEstrategiaId(e != null ? e.getId() : null);
        le.setType(type);
        le.setAmount(amount);
        le.setBalanceAfter(w.getBalanceReal());
        le.setReference(reference);
        le.setTimestamp(Instant.now());
        ledgerRepo.save(le);
    }
}