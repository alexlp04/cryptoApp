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

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class AccountingService {

    @Autowired
    private WalletRepository walletRepo;

    @Autowired
    private InstanciaEstrategiaRepository estrategiaRepo;

    @Autowired
    private LedgerRepository ledgerRepo;

    // =========================
    // ACTIVAR ESTRATEGIA (Reserva de capital inicial)
    // =========================
    public InstanciaEstrategia activateStrategy(Long walletId, Long estrategiaId, BigDecimal capital) {
        try {
            // Usamos Lock para asegurar que nadie más modifique la wallet durante la
            // lectura/escritura
            Wallet w = walletRepo.findByIdWithLock(walletId)
                    .orElseThrow(() -> new RuntimeException("Wallet no encontrada"));
            InstanciaEstrategia e = estrategiaRepo.findById(estrategiaId)
                    .orElseThrow(() -> new RuntimeException("Estrategia no encontrada"));

            if (w.getBalanceDisponible().compareTo(capital) < 0) {
                throw new RuntimeException("Fondos insuficientes en balance disponible");
            }

            // Restar del disponible de la wallet
            w.setBalanceDisponible(w.getBalanceDisponible().subtract(capital));

            // Asignar al silo de la estrategia
            e.setWalletAsociada(walletId);
            e.setCapitalAsignado(capital);
            
            e.setEstado("ACTIVA");

            saveLedger(w, e, LedgerType.RESERVED, capital.negate());

            walletRepo.save(w);
            return estrategiaRepo.save(e);
        } catch (org.springframework.dao.DataAccessException ex) {
            // Captura errores específicos de base de datos (SQL, Constraints, tablas
            // faltantes)
            System.err.println("=== ERROR DE BASE DE DATOS ===");
            System.err.println("Mensaje: " + ex.getMessage());
            if (ex.getRootCause() != null) {
                System.err.println("Causa raíz: " + ex.getRootCause().getMessage());
            }
            throw ex; // Re-lanzar para que la transacción haga rollback
        } catch (Exception ex) {
            // Captura cualquier otro error (NullPointer, etc.)
            System.err.println("=== ERROR GENERAL ===");
            ex.printStackTrace();
            throw ex;
        }
    }

    // =========================
    // CREAR ORDEN (Commit de capital del silo a la orden)
    // =========================
    public void commitCapital(Long estrategiaId, BigDecimal margin, BigDecimal risk) {
        // Bloqueamos la instancia para actualizar sus balances internos
        InstanciaEstrategia e = estrategiaRepo.findByIdWithLock(estrategiaId)
                .orElseThrow(() -> new RuntimeException("Instancia no encontrada"));

        if (e.getCapitalReservado().compareTo(margin) < 0) {
            throw new RuntimeException("Capital reservado insuficiente en la estrategia");
        }

        e.setCapitalReservado(e.getCapitalReservado().subtract(margin));
        e.setCapitalComprometido(e.getCapitalComprometido().add(margin));
        e.setRiesgoAbierto(e.getRiesgoAbierto().add(risk));

        estrategiaRepo.save(e);
    }

    // =========================
    // EJECUCIÓN REAL (Cobro de comisiones del exchange)
    // =========================
    public void applyFee(Long walletId, BigDecimal fee) {
        Wallet w = walletRepo.findByIdWithLock(walletId)
                .orElseThrow(() -> new RuntimeException("Wallet no encontrada"));

        w.setBalanceReal(w.getBalanceReal().subtract(fee));

        saveLedger(w, null, LedgerType.FEE, fee.negate());
        walletRepo.save(w);
    }

    // =========================
    // CIERRE DE TRADE (Liberación de margen y aplicación de PnL)
    // =========================
    public void closeTrade(Long walletId, Long estrategiaId, BigDecimal margin, BigDecimal pnl, BigDecimal risk) {
        Wallet w = walletRepo.findByIdWithLock(walletId).orElseThrow();
        InstanciaEstrategia e = estrategiaRepo.findByIdWithLock(estrategiaId).orElseThrow();

        // 1. Liberar margen y actualizar el capital del silo con el PnL (Interés
        // compuesto local)
        e.setCapitalComprometido(e.getCapitalComprometido().subtract(margin));
        e.setCapitalReservado(e.getCapitalReservado().add(margin).add(pnl));
        e.setRiesgoAbierto(e.getRiesgoAbierto().subtract(risk));

        // 2. Aplicar PnL al balance real de la wallet
        w.setBalanceReal(w.getBalanceReal().add(pnl));

        saveLedger(w, e, LedgerType.REALIZED_PNL, pnl);

        walletRepo.save(w);
        estrategiaRepo.save(e);
    }

    // =========================
    // FINALIZAR ESTRATEGIA (Devolver capital sobrante a la wallet)
    // =========================
    public void closeStrategy(Long walletId, Long estrategiaId) {
        Wallet w = walletRepo.findByIdWithLock(walletId).orElseThrow();
        InstanciaEstrategia e = estrategiaRepo.findByIdWithLock(estrategiaId).orElseThrow();

        BigDecimal liberar = e.getCapitalReservado();

        // El capital vuelve a estar disponible para otras estrategias
        w.setBalanceDisponible(w.getBalanceDisponible().add(liberar));

        e.setCapitalReservado(BigDecimal.ZERO);
        e.setCapitalAsignado(BigDecimal.ZERO);
        e.setEstado("FINALIZADA");

        saveLedger(w, e, LedgerType.AVAILABLE, liberar);

        walletRepo.save(w);
        estrategiaRepo.save(e);
    }

    // =========================
    // LEDGER (Persistencia del historial)
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