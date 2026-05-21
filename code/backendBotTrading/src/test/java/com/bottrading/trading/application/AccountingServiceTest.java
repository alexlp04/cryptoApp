package com.bottrading.trading.application;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.bottrading.shared.exceptions.ValidationException;
import com.bottrading.strategy.domain.EstadoEstrategia;
import com.bottrading.strategy.domain.InstanciaEstrategia;
import com.bottrading.strategy.domain.InstanciaEstrategiaRepository;
import com.bottrading.trading.application.port.out.LedgerRepositoryPort;
import com.bottrading.trading.domain.LedgerEntry;
import com.bottrading.wallet.application.port.out.WalletRepositoryPort;
import com.bottrading.wallet.domain.Wallet;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AccountingServiceTest {

    @Mock
    private WalletRepositoryPort walletRepo;

    @Mock
    private InstanciaEstrategiaRepository estrategiaRepo;

    @Mock
    private LedgerRepositoryPort ledgerRepo;

    @InjectMocks
    private AccountingService accountingService;

    // ─────────────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────────────
    private Wallet crearWalletTest(Long id, BigDecimal balanceReal, BigDecimal balanceDisponible) {
        Wallet w = new Wallet();
        w.setId(id);
        w.setBalanceReal(balanceReal);
        w.setBalanceDisponible(balanceDisponible);
        w.setNombre("Wallet-" + id);
        w.setUsuarioId(1L);
        return w;
    }

    private InstanciaEstrategia crearInstanciaTest(Long id, BigDecimal capitalReservado,
            BigDecimal capitalComprometido, BigDecimal riesgoAbierto) {
        InstanciaEstrategia e = InstanciaEstrategia.inicializar(
                "RSISMAStrategy", "modelo", "1h", java.util.List.of("BTCUSDT"),
                false, 1L, new BigDecimal("0.01"), capitalReservado);
        e.setId(id);
        e.setCapitalReservado(capitalReservado);
        e.setCapitalComprometido(capitalComprometido);
        e.setRiesgoAbierto(riesgoAbierto);
        e.setEstado(EstadoEstrategia.ACTIVA);
        e.setWalletAsociada(1L);
        return e;
    }

    @BeforeEach
    void configurarSavesDefault() {
        when(walletRepo.save(any(Wallet.class))).thenAnswer(inv -> inv.getArgument(0));
        when(estrategiaRepo.save(any(InstanciaEstrategia.class))).thenAnswer(inv -> inv.getArgument(0));
        when(ledgerRepo.save(any(LedgerEntry.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CONSTRUCCIÓN
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("CONSTRUCCIÓN: Instanciación del servicio")
    class ConstructionTests {

        @Test
        @DisplayName("✓ La instancia no debe ser nula con las dependencias inyectadas")
        void should_create_non_null_instance() {
            assertThat(accountingService, is(notNullValue()));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // activateStrategy()
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("activateStrategy() — Reserva capital y activa estrategia")
    class ActivateStrategyTests {

        @Test
        @DisplayName("✓ Debe reservar capital, cambiar estado a ACTIVA y guardar ledger")
        void should_reserve_capital_and_set_activa() {
            Wallet w = crearWalletTest(1L, new BigDecimal("1000"), new BigDecimal("500"));
            InstanciaEstrategia e = crearInstanciaTest(10L, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
            e.setEstado(EstadoEstrategia.CREADA);

            doReturn(Optional.of(w)).when(walletRepo).findByIdWithLock(1L);
            doReturn(Optional.of(e)).when(estrategiaRepo).findByIdWithLock(10L);

            InstanciaEstrategia result = accountingService.activateStrategy(1L, 10L, new BigDecimal("200"));

            assertThat(result.getEstado(), is(EstadoEstrategia.ACTIVA));
            assertThat(w.getBalanceDisponible(), is(new BigDecimal("300")));
            verify(walletRepo, times(1)).save(w);
            verify(estrategiaRepo, times(1)).save(e);
            verify(ledgerRepo, times(1)).save(any(LedgerEntry.class));
        }

        @Test
        @DisplayName("✓ Debe lanzar ValidationException cuando el balance disponible es insuficiente")
        void should_throw_when_insufficient_funds() {
            Wallet w = crearWalletTest(1L, new BigDecimal("1000"), new BigDecimal("100"));
            InstanciaEstrategia e = crearInstanciaTest(10L, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

            doReturn(Optional.of(w)).when(walletRepo).findByIdWithLock(1L);
            doReturn(Optional.of(e)).when(estrategiaRepo).findByIdWithLock(10L);

            assertThrows(ValidationException.class,
                    () -> accountingService.activateStrategy(1L, 10L, new BigDecimal("500")));
            verify(walletRepo, never()).save(any());
        }

        @Test
        @DisplayName("✓ Debe lanzar ValidationException cuando la wallet no existe")
        void should_throw_when_wallet_not_found() {
            doReturn(Optional.empty()).when(walletRepo).findByIdWithLock(anyLong());

            assertThrows(ValidationException.class,
                    () -> accountingService.activateStrategy(99L, 10L, new BigDecimal("100")));
        }

        @Test
        @DisplayName("✓ Debe lanzar ValidationException cuando la estrategia no existe")
        void should_throw_when_strategy_not_found() {
            Wallet w = crearWalletTest(1L, new BigDecimal("1000"), new BigDecimal("500"));
            doReturn(Optional.of(w)).when(walletRepo).findByIdWithLock(1L);
            doReturn(Optional.empty()).when(estrategiaRepo).findByIdWithLock(anyLong());

            assertThrows(ValidationException.class,
                    () -> accountingService.activateStrategy(1L, 99L, new BigDecimal("100")));
        }

        @Test
        @DisplayName("✓ El capital exacto disponible no debe lanzar excepción")
        void should_succeed_when_capital_equals_available() {
            Wallet w = crearWalletTest(1L, new BigDecimal("1000"), new BigDecimal("200"));
            InstanciaEstrategia e = crearInstanciaTest(10L, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

            doReturn(Optional.of(w)).when(walletRepo).findByIdWithLock(1L);
            doReturn(Optional.of(e)).when(estrategiaRepo).findByIdWithLock(10L);

            accountingService.activateStrategy(1L, 10L, new BigDecimal("200"));

            assertThat(w.getBalanceDisponible().compareTo(BigDecimal.ZERO), is(0));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // pauseStrategyTemporarily()
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("pauseStrategyTemporarily() — Pausa sin liberar capital")
    class PauseStrategyTests {

        @Test
        @DisplayName("✓ Debe cambiar estado a DETENIDA y guardar ledger de evento")
        void should_set_detenida_and_save_ledger() {
            InstanciaEstrategia e = crearInstanciaTest(10L, new BigDecimal("200"), BigDecimal.ZERO, BigDecimal.ZERO);
            doReturn(Optional.of(e)).when(estrategiaRepo).findByIdWithLock(10L);

            accountingService.pauseStrategyTemporarily(1L, 10L);

            assertThat(e.getEstado(), is(EstadoEstrategia.DETENIDA));
            verify(ledgerRepo, times(1)).save(any(LedgerEntry.class));
            verify(estrategiaRepo, times(1)).save(e);
        }

        @Test
        @DisplayName("✓ Debe lanzar ValidationException cuando la estrategia no existe")
        void should_throw_when_strategy_not_found() {
            doReturn(Optional.empty()).when(estrategiaRepo).findByIdWithLock(anyLong());

            assertThrows(ValidationException.class,
                    () -> accountingService.pauseStrategyTemporarily(1L, 99L));
        }

        @Test
        @DisplayName("✓ No debe modificar el capital reservado (solo congela)")
        void should_not_modify_reserved_capital() {
            InstanciaEstrategia e = crearInstanciaTest(10L, new BigDecimal("300"), BigDecimal.ZERO, BigDecimal.ZERO);
            doReturn(Optional.of(e)).when(estrategiaRepo).findByIdWithLock(10L);

            accountingService.pauseStrategyTemporarily(1L, 10L);

            assertThat(e.getCapitalReservado(), is(new BigDecimal("300")));
            verify(walletRepo, never()).save(any());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // closeStrategy()
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("closeStrategy() — Termina y retorna capital a la wallet")
    class CloseStrategyTests {

        @Test
        @DisplayName("✓ Debe devolver capital reservado al balance disponible y marcar TERMINADA")
        void should_return_reserved_capital_and_set_terminada() {
            Wallet w = crearWalletTest(1L, new BigDecimal("800"), new BigDecimal("100"));
            InstanciaEstrategia e = crearInstanciaTest(10L, new BigDecimal("200"), BigDecimal.ZERO, BigDecimal.ZERO);

            doReturn(Optional.of(w)).when(walletRepo).findByIdWithLock(1L);
            doReturn(Optional.of(e)).when(estrategiaRepo).findByIdWithLock(10L);

            accountingService.closeStrategy(1L, 10L);

            assertThat(e.getEstado(), is(EstadoEstrategia.TERMINADA));
            assertThat(w.getBalanceDisponible(), is(new BigDecimal("300")));
            assertThat(e.getCapitalReservado().compareTo(BigDecimal.ZERO), is(0));
            assertThat(e.getCapitalAsignado().compareTo(BigDecimal.ZERO), is(0));
            verify(walletRepo, times(1)).save(w);
            verify(estrategiaRepo, times(1)).save(e);
            verify(ledgerRepo, times(1)).save(any(LedgerEntry.class));
        }

        @Test
        @DisplayName("✓ No debe guardar ledger de devolución cuando el capital reservado es cero")
        void should_not_save_ledger_when_no_reserved_capital() {
            Wallet w = crearWalletTest(1L, new BigDecimal("800"), new BigDecimal("100"));
            InstanciaEstrategia e = crearInstanciaTest(10L, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

            doReturn(Optional.of(w)).when(walletRepo).findByIdWithLock(1L);
            doReturn(Optional.of(e)).when(estrategiaRepo).findByIdWithLock(10L);

            accountingService.closeStrategy(1L, 10L);

            assertThat(e.getEstado(), is(EstadoEstrategia.TERMINADA));
            verify(ledgerRepo, never()).save(any());
        }

        @Test
        @DisplayName("✓ Debe lanzar ValidationException cuando la wallet no existe")
        void should_throw_when_wallet_not_found() {
            doReturn(Optional.empty()).when(walletRepo).findByIdWithLock(anyLong());

            assertThrows(ValidationException.class,
                    () -> accountingService.closeStrategy(99L, 10L));
        }

        @Test
        @DisplayName("✓ Debe lanzar ValidationException cuando la estrategia no existe")
        void should_throw_when_strategy_not_found() {
            Wallet w = crearWalletTest(1L, new BigDecimal("800"), new BigDecimal("100"));
            doReturn(Optional.of(w)).when(walletRepo).findByIdWithLock(1L);
            doReturn(Optional.empty()).when(estrategiaRepo).findByIdWithLock(anyLong());

            assertThrows(ValidationException.class,
                    () -> accountingService.closeStrategy(1L, 99L));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // commitCapital()
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("commitCapital() — Mueve capital de Reservado a Comprometido")
    class CommitCapitalTests {

        @Test
        @DisplayName("✓ Debe mover el margen de reservado a comprometido y guardar ledger")
        void should_move_margin_from_reserved_to_committed() {
            InstanciaEstrategia e = crearInstanciaTest(10L, new BigDecimal("500"), new BigDecimal("100"),
                    new BigDecimal("0.05"));
            doReturn(Optional.of(e)).when(estrategiaRepo).findByIdWithLock(10L);

            accountingService.commitCapital(10L, new BigDecimal("200"), new BigDecimal("0.02"));

            assertThat(e.getCapitalReservado(), is(new BigDecimal("300")));
            assertThat(e.getCapitalComprometido(), is(new BigDecimal("300")));
            assertThat(e.getRiesgoAbierto(), is(new BigDecimal("0.07")));
            verify(ledgerRepo, times(1)).save(any(LedgerEntry.class));
            verify(estrategiaRepo, times(1)).save(e);
        }

        @Test
        @DisplayName("✓ Debe lanzar ValidationException cuando el capital reservado es insuficiente")
        void should_throw_when_insufficient_reserved_capital() {
            InstanciaEstrategia e = crearInstanciaTest(10L, new BigDecimal("50"), BigDecimal.ZERO, BigDecimal.ZERO);
            doReturn(Optional.of(e)).when(estrategiaRepo).findByIdWithLock(10L);

            assertThrows(ValidationException.class,
                    () -> accountingService.commitCapital(10L, new BigDecimal("100"), new BigDecimal("0.01")));
            verify(ledgerRepo, never()).save(any());
        }

        @Test
        @DisplayName("✓ Debe lanzar ValidationException cuando la estrategia no existe")
        void should_throw_when_strategy_not_found() {
            doReturn(Optional.empty()).when(estrategiaRepo).findByIdWithLock(anyLong());

            assertThrows(ValidationException.class,
                    () -> accountingService.commitCapital(99L, new BigDecimal("100"), new BigDecimal("0.01")));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // closeTrade()
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("closeTrade() — Cierre de operación con PnL")
    class CloseTradeTests {

        @Test
        @DisplayName("✓ Debe liberar margen, aplicar PnL positivo y actualizar balanceReal")
        void should_release_margin_and_apply_positive_pnl() {
            Wallet w = crearWalletTest(1L, new BigDecimal("1000"), new BigDecimal("100"));
            InstanciaEstrategia e = crearInstanciaTest(10L, new BigDecimal("300"), new BigDecimal("200"),
                    new BigDecimal("0.02"));

            doReturn(Optional.of(w)).when(walletRepo).findByIdWithLock(1L);
            doReturn(Optional.of(e)).when(estrategiaRepo).findByIdWithLock(10L);

            BigDecimal pnl = new BigDecimal("50");
            accountingService.closeTrade(1L, 10L, new BigDecimal("200"), pnl, new BigDecimal("0.02"));

            // capitalComprometido -= margin (200 - 200 = 0)
            assertThat(e.getCapitalComprometido().compareTo(BigDecimal.ZERO), is(0));
            // capitalReservado += margin + pnl (300 + 200 + 50 = 550)
            assertThat(e.getCapitalReservado(), is(new BigDecimal("550")));
            // riesgoAbierto -= risk (0.02 - 0.02 = 0)
            assertThat(e.getRiesgoAbierto().compareTo(BigDecimal.ZERO), is(0));
            // balanceReal += pnl (1000 + 50 = 1050)
            assertThat(w.getBalanceReal(), is(new BigDecimal("1050")));
            verify(ledgerRepo, times(1)).save(any(LedgerEntry.class));
        }

        @Test
        @DisplayName("✓ Debe aplicar PnL negativo (pérdida) correctamente")
        void should_apply_negative_pnl_correctly() {
            Wallet w = crearWalletTest(1L, new BigDecimal("1000"), new BigDecimal("100"));
            InstanciaEstrategia e = crearInstanciaTest(10L, new BigDecimal("300"), new BigDecimal("200"),
                    new BigDecimal("0.02"));

            doReturn(Optional.of(w)).when(walletRepo).findByIdWithLock(1L);
            doReturn(Optional.of(e)).when(estrategiaRepo).findByIdWithLock(10L);

            BigDecimal pnl = new BigDecimal("-30");
            accountingService.closeTrade(1L, 10L, new BigDecimal("200"), pnl, new BigDecimal("0.02"));

            // balanceReal -= 30 (1000 - 30 = 970)
            assertThat(w.getBalanceReal(), is(new BigDecimal("970")));
            // capitalReservado: 300 + 200 - 30 = 470
            assertThat(e.getCapitalReservado(), is(new BigDecimal("470")));
        }

        @Test
        @DisplayName("✓ RiesgoAbierto no debe ser negativo si risk > riesgoAbierto")
        void should_clamp_riesgo_abierto_to_zero() {
            Wallet w = crearWalletTest(1L, new BigDecimal("1000"), new BigDecimal("100"));
            InstanciaEstrategia e = crearInstanciaTest(10L, new BigDecimal("300"), new BigDecimal("200"),
                    new BigDecimal("0.01"));  // solo 0.01

            doReturn(Optional.of(w)).when(walletRepo).findByIdWithLock(1L);
            doReturn(Optional.of(e)).when(estrategiaRepo).findByIdWithLock(10L);

            // risk = 0.05 > riesgoAbierto = 0.01 → debería resultar en 0
            accountingService.closeTrade(1L, 10L, new BigDecimal("200"), BigDecimal.ZERO, new BigDecimal("0.05"));

            assertThat(e.getRiesgoAbierto().compareTo(BigDecimal.ZERO), is(0));
        }

        @Test
        @DisplayName("✓ Debe lanzar ValidationException cuando la wallet no existe")
        void should_throw_when_wallet_not_found() {
            doReturn(Optional.empty()).when(walletRepo).findByIdWithLock(anyLong());

            assertThrows(ValidationException.class,
                    () -> accountingService.closeTrade(99L, 10L,
                            new BigDecimal("100"), BigDecimal.ZERO, new BigDecimal("0.01")));
        }

        @Test
        @DisplayName("✓ Debe lanzar ValidationException cuando la estrategia no existe")
        void should_throw_when_strategy_not_found() {
            Wallet w = crearWalletTest(1L, new BigDecimal("1000"), new BigDecimal("100"));
            doReturn(Optional.of(w)).when(walletRepo).findByIdWithLock(1L);
            doReturn(Optional.empty()).when(estrategiaRepo).findByIdWithLock(anyLong());

            assertThrows(ValidationException.class,
                    () -> accountingService.closeTrade(1L, 99L,
                            new BigDecimal("100"), BigDecimal.ZERO, new BigDecimal("0.01")));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // applyFee()
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("applyFee() — Aplicar comisión a la wallet")
    class ApplyFeeTests {

        @Test
        @DisplayName("✓ Debe restar la comisión del balanceReal y guardar ledger")
        void should_subtract_fee_from_balance_real() {
            Wallet w = crearWalletTest(1L, new BigDecimal("1000"), new BigDecimal("200"));
            doReturn(Optional.of(w)).when(walletRepo).findByIdWithLock(1L);

            accountingService.applyFee(1L, new BigDecimal("5"));

            assertThat(w.getBalanceReal(), is(new BigDecimal("995")));
            verify(walletRepo, times(1)).save(w);
            verify(ledgerRepo, times(1)).save(any(LedgerEntry.class));
        }

        @Test
        @DisplayName("✓ Debe lanzar ValidationException cuando la wallet no existe")
        void should_throw_when_wallet_not_found() {
            doReturn(Optional.empty()).when(walletRepo).findByIdWithLock(anyLong());

            assertThrows(ValidationException.class,
                    () -> accountingService.applyFee(99L, new BigDecimal("5")));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CONTRATO DE PUERTOS
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("Contrato de interfaz AccountingUseCase")
    class ContratoPuertoEntradaTests {

        @Test
        @DisplayName("✓ AccountingService debe implementar AccountingUseCase")
        void should_implement_accountingUseCase_interface() {
            assertThat(accountingService instanceof
                    com.bottrading.trading.application.port.in.AccountingUseCase,
                    is(true));
        }
    }
}
