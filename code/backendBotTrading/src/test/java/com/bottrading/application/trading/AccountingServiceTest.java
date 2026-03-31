package com.bottrading.application.trading;

import com.bottrading.domain.strategy.InstanciaEstrategia;
import com.bottrading.domain.strategy.InstanciaEstrategiaRepository;
import com.bottrading.domain.trading.LedgerEntry;
import com.bottrading.domain.trading.LedgerRepository;
import com.bottrading.domain.trading.LedgerType;
import com.bottrading.domain.wallet.Wallet;
import com.bottrading.domain.wallet.WalletRepository;
import com.bottrading.exceptions.ValidationException;
import com.bottrading.utils.AppConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountingServiceTest {

    @Mock
    private WalletRepository walletRepo;

    @Mock
    private InstanciaEstrategiaRepository estrategiaRepo;

    @Mock
    private LedgerRepository ledgerRepo;

    @InjectMocks
    private AccountingService service;

    @Nested
    @DisplayName("Construction")
    class ConstructionTests {

        @Test
        void should_create_service() {
            assertNotNull(service);
        }
    }

    @Nested
    @DisplayName("activateStrategy")
    class ActivateStrategyTests {

        @Test
        void should_activate_strategy_and_reserve_capital() {
            Wallet wallet = wallet(1L, "1000.00", "1200.00");
            InstanciaEstrategia estrategia = estrategia(10L, 1L, "200.00", "200.00", "0.02");

            when(walletRepo.findByIdWithLock(1L)).thenReturn(Optional.of(wallet));
            when(estrategiaRepo.findById(10L)).thenReturn(Optional.of(estrategia));
            when(estrategiaRepo.save(any(InstanciaEstrategia.class))).thenAnswer(i -> i.getArgument(0));

            InstanciaEstrategia out = service.activateStrategy(1L, 10L, new BigDecimal("250.00"));

            assertEquals("750.00", wallet.getBalanceDisponible().toPlainString());
            assertEquals("250.00", out.getCapitalAsignado().toPlainString());
            assertEquals("ACTIVA", out.getEstado());
            verify(walletRepo, times(1)).save(wallet);
            verify(ledgerRepo, times(1)).save(any(LedgerEntry.class));
        }

        @Test
        void should_throw_when_wallet_not_found() {
            when(walletRepo.findByIdWithLock(1L)).thenReturn(Optional.empty());
            assertThrows(RuntimeException.class, () -> service.activateStrategy(1L, 10L, new BigDecimal("100")));
            verify(estrategiaRepo, never()).findById(10L);
        }

        @Test
        void should_throw_when_strategy_not_found() {
            when(walletRepo.findByIdWithLock(1L)).thenReturn(Optional.of(wallet(1L, "1000", "1000")));
            when(estrategiaRepo.findById(10L)).thenReturn(Optional.empty());
            assertThrows(RuntimeException.class, () -> service.activateStrategy(1L, 10L, new BigDecimal("100")));
        }

        @Test
        void should_throw_validation_when_insufficient_balance() {
            when(walletRepo.findByIdWithLock(1L)).thenReturn(Optional.of(wallet(1L, "50", "50")));
            when(estrategiaRepo.findById(10L)).thenReturn(Optional.of(estrategia(10L, 1L, "100", "100", "0.02")));

            assertThrows(ValidationException.class,
                () -> service.activateStrategy(1L, 10L, new BigDecimal("100")));

            verify(walletRepo, never()).save(any(Wallet.class));
        }
    }

    @Nested
    @DisplayName("pauseStrategyTemporarily")
    class PauseTests {

        @Test
        void should_pause_strategy_and_write_ledger_marker() {
            InstanciaEstrategia e = estrategia(10L, 1L, "100", "100", "0.02");
            e.setEstado("ACTIVA");
            when(estrategiaRepo.findByIdWithLock(10L)).thenReturn(Optional.of(e));

            service.pauseStrategyTemporarily(1L, 10L);

            assertEquals("DETENIDA", e.getEstado());
            ArgumentCaptor<LedgerEntry> captor = ArgumentCaptor.forClass(LedgerEntry.class);
            verify(ledgerRepo).save(captor.capture());
            assertEquals(LedgerType.STRATEGY_PAUSED, captor.getValue().getType());
            verify(estrategiaRepo).save(e);
        }

        @Test
        void should_throw_when_strategy_missing() {
            when(estrategiaRepo.findByIdWithLock(10L)).thenReturn(Optional.empty());
            assertThrows(Exception.class, () -> service.pauseStrategyTemporarily(1L, 10L));
        }
    }

    @Nested
    @DisplayName("closeStrategy")
    class CloseStrategyTests {

        @Test
        void should_release_reserved_capital_and_terminate() {
            Wallet w = wallet(1L, "500", "500");
            InstanciaEstrategia e = estrategia(10L, 1L, "200", "200", "0.02");
            e.setEstado("ACTIVA");

            when(walletRepo.findByIdWithLock(1L)).thenReturn(Optional.of(w));
            when(estrategiaRepo.findByIdWithLock(10L)).thenReturn(Optional.of(e));

            service.closeStrategy(1L, 10L);

            assertEquals("700", w.getBalanceDisponible().toPlainString());
            assertEquals("0", e.getCapitalReservado().toPlainString());
            assertEquals("0", e.getCapitalAsignado().toPlainString());
            assertEquals(AppConstants.KEY_TERMINADA, e.getEstado());
            verify(ledgerRepo, times(1)).save(any(LedgerEntry.class));
            verify(walletRepo).save(w);
            verify(estrategiaRepo).save(e);
        }

        @Test
        void should_terminate_without_ledger_when_no_reserved_capital() {
            Wallet w = wallet(1L, "500", "500");
            InstanciaEstrategia e = estrategia(10L, 1L, "0", "0", "0.02");

            when(walletRepo.findByIdWithLock(1L)).thenReturn(Optional.of(w));
            when(estrategiaRepo.findByIdWithLock(10L)).thenReturn(Optional.of(e));

            service.closeStrategy(1L, 10L);

            verify(ledgerRepo, never()).save(any(LedgerEntry.class));
            assertEquals(AppConstants.KEY_TERMINADA, e.getEstado());
        }
    }

    @Nested
    @DisplayName("commitCapital")
    class CommitCapitalTests {

        @Test
        void should_move_reserved_to_committed_and_track_risk() {
            InstanciaEstrategia e = estrategia(10L, 1L, "300", "500", "0.02");
            e.setCapitalComprometido(new BigDecimal("50"));
            e.setRiesgoAbierto(new BigDecimal("0.01"));
            when(estrategiaRepo.findByIdWithLock(10L)).thenReturn(Optional.of(e));

            service.commitCapital(10L, new BigDecimal("100"), new BigDecimal("0.02"));

            assertEquals("200", e.getCapitalReservado().toPlainString());
            assertEquals("150", e.getCapitalComprometido().toPlainString());
            assertEquals("0.03", e.getRiesgoAbierto().toPlainString());
            verify(ledgerRepo).save(any(LedgerEntry.class));
            verify(estrategiaRepo).save(e);
        }

        @Test
        void should_throw_validation_when_reserved_is_not_enough() {
            InstanciaEstrategia e = estrategia(10L, 1L, "20", "100", "0.02");
            when(estrategiaRepo.findByIdWithLock(10L)).thenReturn(Optional.of(e));

            assertThrows(ValidationException.class,
                () -> service.commitCapital(10L, new BigDecimal("30"), new BigDecimal("0.01")));

            verify(ledgerRepo, never()).save(any(LedgerEntry.class));
        }

        @Test
        void should_throw_when_strategy_missing() {
            when(estrategiaRepo.findByIdWithLock(10L)).thenReturn(Optional.empty());
            assertThrows(RuntimeException.class,
                () -> service.commitCapital(10L, new BigDecimal("10"), new BigDecimal("0.01")));
        }
    }

    @Nested
    @DisplayName("closeTrade")
    class CloseTradeTests {

        @Test
        void should_release_margin_apply_pnl_and_reduce_risk() {
            Wallet w = wallet(1L, "300", "1000");
            InstanciaEstrategia e = estrategia(10L, 1L, "100", "300", "0.02");
            e.setCapitalComprometido(new BigDecimal("150"));
            e.setRiesgoAbierto(new BigDecimal("0.03"));

            when(walletRepo.findByIdWithLock(1L)).thenReturn(Optional.of(w));
            when(estrategiaRepo.findByIdWithLock(10L)).thenReturn(Optional.of(e));

            service.closeTrade(1L, 10L, new BigDecimal("100"), new BigDecimal("25"), new BigDecimal("0.02"));

            assertEquals("50", e.getCapitalComprometido().toPlainString());
            assertEquals("225", e.getCapitalReservado().toPlainString());
            assertEquals("0.01", e.getRiesgoAbierto().toPlainString());
            assertEquals("1025", w.getBalanceReal().toPlainString());
            verify(ledgerRepo).save(any(LedgerEntry.class));
            verify(walletRepo).save(w);
            verify(estrategiaRepo).save(e);
        }

        @Test
        void should_clamp_risk_to_zero_when_negative_result() {
            Wallet w = wallet(1L, "300", "1000");
            InstanciaEstrategia e = estrategia(10L, 1L, "100", "300", "0.02");
            e.setCapitalComprometido(new BigDecimal("100"));
            e.setRiesgoAbierto(new BigDecimal("0.01"));

            when(walletRepo.findByIdWithLock(1L)).thenReturn(Optional.of(w));
            when(estrategiaRepo.findByIdWithLock(10L)).thenReturn(Optional.of(e));

            service.closeTrade(1L, 10L, new BigDecimal("50"), new BigDecimal("-10"), new BigDecimal("0.05"));

            assertEquals("0", e.getRiesgoAbierto().toPlainString());
        }

        @Test
        void should_throw_when_wallet_missing() {
            when(walletRepo.findByIdWithLock(1L)).thenReturn(Optional.empty());
            assertThrows(Exception.class,
                () -> service.closeTrade(1L, 10L, new BigDecimal("10"), new BigDecimal("1"), new BigDecimal("0.01")));
        }
    }

    @Nested
    @DisplayName("applyFee")
    class ApplyFeeTests {

        @Test
        void should_apply_fee_and_save_ledger() {
            Wallet w = wallet(1L, "500", "1000");
            when(walletRepo.findByIdWithLock(1L)).thenReturn(Optional.of(w));

            service.applyFee(1L, new BigDecimal("2.5"));

            assertEquals("997.5", w.getBalanceReal().toPlainString());
            verify(ledgerRepo, times(1)).save(any(LedgerEntry.class));
            verify(walletRepo, times(1)).save(w);
        }

        @Test
        void should_apply_zero_fee_without_error() {
            Wallet w = wallet(1L, "500", "1000");
            when(walletRepo.findByIdWithLock(1L)).thenReturn(Optional.of(w));

            assertDoesNotThrow(() -> service.applyFee(1L, BigDecimal.ZERO));
            assertEquals("1000", w.getBalanceReal().toPlainString());
        }

        @Test
        void should_throw_when_wallet_missing() {
            when(walletRepo.findByIdWithLock(1L)).thenReturn(Optional.empty());
            assertThrows(RuntimeException.class, () -> service.applyFee(1L, new BigDecimal("1")));
        }
    }

    private Wallet wallet(Long id, String disponible, String real) {
        Wallet w = new Wallet();
        w.setId(id);
        w.setBalanceDisponible(new BigDecimal(disponible));
        w.setBalanceReal(new BigDecimal(real));
        return w;
    }

    private InstanciaEstrategia estrategia(Long id, Long walletId, String reservado, String asignado, String risk) {
        InstanciaEstrategia e = new InstanciaEstrategia();
        e.setId(id);
        e.setWalletAsociada(walletId);
        e.setCapitalReservado(new BigDecimal(reservado));
        e.setCapitalAsignado(new BigDecimal(asignado));
        e.setCapitalComprometido(BigDecimal.ZERO);
        e.setRiesgoAbierto(BigDecimal.ZERO);
        e.setRiskPerTrade(new BigDecimal(risk));
        e.setEstado("ACTIVA");
        return e;
    }
}
