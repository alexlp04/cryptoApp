package com.bottrading.e2e;

import com.bottrading.application.strategy.StrategyLifecycleApplicationService;
import com.bottrading.application.trading.AccountingService;
import com.bottrading.application.trading.PaperTradingService;
import com.bottrading.beans.SignalDTO;
import com.bottrading.domain.strategy.InstanciaEstrategia;
import com.bottrading.domain.strategy.InstanciaEstrategiaRepository;
import com.bottrading.domain.trading.LedgerEntry;
import com.bottrading.domain.trading.LedgerRepository;
import com.bottrading.domain.trading.LedgerType;
import com.bottrading.domain.trading.Posicion;
import com.bottrading.domain.trading.PosicionRepository;
import com.bottrading.domain.wallet.Wallet;
import com.bottrading.domain.wallet.WalletRepository;
import com.bottrading.infrastructure.bridge.StrategyRuntimeCoordinator;
import com.bottrading.infrastructure.cache.StatsCache;
import com.bottrading.infrastructure.persistence.FileService;
import com.bottrading.utils.AppConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BLOQUE 3 - E2E de ciclo de vida de estrategia en Java.
 *
 * Valida flujo transversal entre StrategyLifecycleApplicationService,
 * PaperTradingService y AccountingService usando puertos mockeados.
 */
@ExtendWith(MockitoExtension.class)
class StrategyLifecycleE2ETest {

    @Mock
    private InstanciaEstrategiaRepository instanciaRepo;

    @Mock
    private WalletRepository walletRepo;

    @Mock
    private LedgerRepository ledgerRepo;

    @Mock
    private PosicionRepository posicionRepo;

    @Mock
    private FileService fileService;

    @Mock
    private StatsCache statsCache;

    @Mock
    private StrategyRuntimeCoordinator runtimeCoordinator;

    private AccountingService accountingService;
    private PaperTradingService paperTradingService;
    private StrategyLifecycleApplicationService lifecycleService;

    @BeforeEach
    void setUp() {
        accountingService = new AccountingService(walletRepo, instanciaRepo, ledgerRepo);
        paperTradingService = new PaperTradingService(accountingService, instanciaRepo, posicionRepo, fileService, statsCache);
        lifecycleService = new StrategyLifecycleApplicationService(instanciaRepo, accountingService, runtimeCoordinator);
    }

    @Nested
    @DisplayName("Flujo E2E: start -> runtime -> trading -> close")
    class StrategyLifecycleFlow {

        @Test
        @DisplayName("Debe iniciar estrategia, reservar capital y lanzar runtime")
        void should_start_strategy_and_reserve_capital_and_launch_runtime() {
            Long walletId = 10L;
            BigDecimal capital = new BigDecimal("2000.00");

            Wallet wallet = wallet(walletId, "10000.00", "10000.00");

            ArgumentCaptor<InstanciaEstrategia> instanciaCaptor = ArgumentCaptor.forClass(InstanciaEstrategia.class);
            when(instanciaRepo.save(instanciaCaptor.capture())).thenAnswer(invocation -> {
                InstanciaEstrategia saved = invocation.getArgument(0);
                if (saved.getId() == null) {
                    saved.setId(101L);
                }
                return saved;
            });
            when(walletRepo.findByIdWithLock(walletId)).thenReturn(Optional.of(wallet));
            when(instanciaRepo.findById(101L)).thenAnswer(invocation -> Optional.of(instanciaCaptor.getValue()));

            lifecycleService.iniciarTradeRT(
                "RSISMAStrategy",
                "N/A",
                "1h",
                List.of("BTCUSDT", "ETHUSDT"),
                false,
                walletId,
                new BigDecimal("0.10"),
                capital
            );

            InstanciaEstrategia creada = instanciaCaptor.getValue();
            assertNotNull(creada);
            assertEquals("ACTIVA", creada.getEstado());
            assertEquals(0, wallet.getBalanceDisponible().compareTo(new BigDecimal("8000.00")));

            verify(runtimeCoordinator, times(1))
                .ejecutarTradeEnTiempoReal(eq(creada), eq(List.of("BTCUSDT", "ETHUSDT")));
            verify(ledgerRepo, atLeastOnce()).save(any(LedgerEntry.class));
        }

        @Test
        @DisplayName("Debe procesar BUY y SELL actualizando posicion, capital y PnL")
        void should_process_buy_and_sell_and_update_accounting() {
            Long walletId = 20L;
            Long instanciaId = 201L;

            Wallet wallet = wallet(walletId, "10000.00", "10000.00");
            InstanciaEstrategia instancia = instanciaActiva(instanciaId, walletId, "2000.00", "0.10");

            Posicion[] posicionRef = new Posicion[1];

            when(instanciaRepo.findByIdWithLock(instanciaId)).thenReturn(Optional.of(instancia));
            when(walletRepo.findByIdWithLock(walletId)).thenReturn(Optional.of(wallet));

            when(posicionRepo.existsByInstanciaAndSimboloAndAbiertaTrue(instancia, "BTCUSDT")).thenReturn(false);
            when(posicionRepo.save(any(Posicion.class))).thenAnswer(invocation -> {
                Posicion pos = invocation.getArgument(0);
                if (pos.getId() == null) {
                    pos.setId(501L);
                }
                posicionRef[0] = pos;
                return pos;
            });
            when(posicionRepo.findByInstanciaAndSimboloAndAbiertaTrue(instancia, "BTCUSDT"))
                .thenAnswer(invocation -> Optional.ofNullable(posicionRef[0]));

            Map<String, Object> currentStats = new HashMap<>();
            currentStats.put("op_ganadas", 0);
            currentStats.put("op_perdidas", 0);
            currentStats.put("retorno_total", BigDecimal.ZERO);
            when(statsCache.getStats("RSISMAStrategy", "1h", "BTCUSDT")).thenReturn(currentStats);

            SignalDTO buy = signal("BUY", "BTCUSDT", "1h", "100.00", 1_710_000_000_000L);
            SignalDTO sell = signal("SELL", "BTCUSDT", "1h", "110.00", 1_710_000_003_600L);

            paperTradingService.onSignal(instanciaId, buy);
            assertEquals(0, instancia.getCapitalReservado().compareTo(new BigDecimal("1800.00")));
            assertEquals(0, instancia.getCapitalComprometido().compareTo(new BigDecimal("200.00")));

            paperTradingService.onSignal(instanciaId, sell);

            assertEquals(0, instancia.getCapitalComprometido().compareTo(BigDecimal.ZERO));
            assertEquals(0, instancia.getCapitalReservado().compareTo(new BigDecimal("2020.00")));
            assertEquals(0, wallet.getBalanceReal().compareTo(new BigDecimal("10020.00")));

            verify(fileService, times(2)).guardarTrade(anyString(), anyString(), eq("BTCUSDT"), anyString(), any(BigDecimal.class), anyLong(), any());
            verify(fileService, times(1)).guardarStats(eq("RSISMAStrategy"), eq("1h"), any(Map.class), eq(false));
            verify(ledgerRepo, atLeastOnce()).save(any(LedgerEntry.class));
        }

        @Test
        @DisplayName("Debe pausar y terminar estrategia liberando fondos")
        void should_pause_and_terminate_strategy_releasing_funds() {
            Long walletId = 30L;
            Long instanciaId = 301L;
            Wallet wallet = wallet(walletId, "10000.00", "5000.00");
            InstanciaEstrategia instancia = instanciaActiva(instanciaId, walletId, "1500.00", "0.05");

            when(instanciaRepo.findById(instanciaId)).thenReturn(Optional.of(instancia));
            when(instanciaRepo.findByIdWithLock(instanciaId)).thenReturn(Optional.of(instancia));
            when(walletRepo.findByIdWithLock(walletId)).thenReturn(Optional.of(wallet));

            lifecycleService.detenerEstrategia(instanciaId);
            assertEquals(AppConstants.KEY_DETENIDA, instancia.getEstado());

            lifecycleService.terminarEstrategia(instanciaId);

            assertEquals(AppConstants.KEY_TERMINADA, instancia.getEstado());
            assertEquals(0, instancia.getCapitalReservado().compareTo(BigDecimal.ZERO));
            assertEquals(0, instancia.getCapitalAsignado().compareTo(BigDecimal.ZERO));
            assertEquals(0, wallet.getBalanceDisponible().compareTo(new BigDecimal("6500.00")));

            verify(runtimeCoordinator, times(2)).detenerEstrategia(instanciaId);
            verify(ledgerRepo, atLeastOnce()).save(any(LedgerEntry.class));
        }

        @Test
        @DisplayName("Debe ignorar señales cuando la estrategia no esta activa")
        void should_ignore_signal_when_strategy_is_not_active() {
            Long instanciaId = 401L;
            InstanciaEstrategia instancia = instanciaActiva(instanciaId, 40L, "1000.00", "0.10");
            instancia.setEstado(AppConstants.KEY_DETENIDA);

            when(instanciaRepo.findByIdWithLock(instanciaId)).thenReturn(Optional.of(instancia));

            SignalDTO buy = signal("BUY", "BTCUSDT", "1h", "100.00", 1_710_000_000_000L);
            paperTradingService.onSignal(instanciaId, buy);

            verify(posicionRepo, never()).save(any(Posicion.class));
            verify(ledgerRepo, never()).save(any(LedgerEntry.class));
            assertTrue(instancia.getCapitalReservado().compareTo(new BigDecimal("1000.00")) == 0);
        }

        @Test
        @DisplayName("Debe reanudar estrategia detenida y relanzar runtime")
        void should_resume_stopped_strategy_and_relaunch_runtime() {
            Long instanciaId = 501L;
            InstanciaEstrategia instancia = instanciaActiva(instanciaId, 50L, "1200.00", "0.08");
            instancia.setEstado(AppConstants.KEY_DETENIDA);

            when(instanciaRepo.findById(instanciaId)).thenReturn(Optional.of(instancia));

            lifecycleService.iniciarEstrategiaDetenida(instanciaId);

            assertEquals(AppConstants.KEY_ACTIVA, instancia.getEstado());
            verify(instanciaRepo, times(1)).save(instancia);
            verify(runtimeCoordinator, times(1)).ejecutarTradeEnTiempoReal(instancia, instancia.getSimbolos());
        }

        @Test
        @DisplayName("No debe reanudar estrategia si no esta en DETENIDA")
        void should_not_resume_strategy_if_state_is_not_stopped() {
            Long instanciaId = 502L;
            InstanciaEstrategia instancia = instanciaActiva(instanciaId, 50L, "1200.00", "0.08");

            when(instanciaRepo.findById(instanciaId)).thenReturn(Optional.of(instancia));

            lifecycleService.iniciarEstrategiaDetenida(instanciaId);

            assertEquals(AppConstants.KEY_ACTIVA, instancia.getEstado());
            verify(runtimeCoordinator, never()).ejecutarTradeEnTiempoReal(any(InstanciaEstrategia.class), any(List.class));
        }

        @Test
        @DisplayName("Debe pausar todas las estrategias activas reportadas por runtime")
        void should_stop_all_active_strategies_from_runtime_registry() {
            Long id1 = 601L;
            Long id2 = 602L;
            InstanciaEstrategia inst1 = instanciaActiva(id1, 60L, "1000.00", "0.05");
            InstanciaEstrategia inst2 = instanciaActiva(id2, 61L, "1000.00", "0.05");

            when(runtimeCoordinator.getIdsEstrategiasActivas()).thenReturn(Set.of(id1, id2));
            when(instanciaRepo.findById(id1)).thenReturn(Optional.of(inst1));
            when(instanciaRepo.findById(id2)).thenReturn(Optional.of(inst2));

            lifecycleService.detenerTodas();

            assertEquals(AppConstants.KEY_DETENIDA, inst1.getEstado());
            assertEquals(AppConstants.KEY_DETENIDA, inst2.getEstado());
            verify(runtimeCoordinator, times(1)).detenerEstrategia(id1);
            verify(runtimeCoordinator, times(1)).detenerEstrategia(id2);
        }

        @Test
        @DisplayName("Debe terminar todas las estrategias activas y devolver capital")
        void should_terminate_all_active_strategies_and_release_capital() {
            Long id1 = 701L;
            Long id2 = 702L;
            Wallet wallet1 = wallet(71L, "5000.00", "1000.00");
            Wallet wallet2 = wallet(72L, "6000.00", "2000.00");
            InstanciaEstrategia inst1 = instanciaActiva(id1, 71L, "800.00", "0.05");
            InstanciaEstrategia inst2 = instanciaActiva(id2, 72L, "900.00", "0.05");

            when(runtimeCoordinator.getIdsEstrategiasActivas()).thenReturn(Set.of(id1, id2));
            when(instanciaRepo.findById(id1)).thenReturn(Optional.of(inst1));
            when(instanciaRepo.findById(id2)).thenReturn(Optional.of(inst2));
            when(instanciaRepo.findByIdWithLock(id1)).thenReturn(Optional.of(inst1));
            when(instanciaRepo.findByIdWithLock(id2)).thenReturn(Optional.of(inst2));
            when(walletRepo.findByIdWithLock(71L)).thenReturn(Optional.of(wallet1));
            when(walletRepo.findByIdWithLock(72L)).thenReturn(Optional.of(wallet2));

            lifecycleService.terminarTodas();

            assertEquals(AppConstants.KEY_TERMINADA, inst1.getEstado());
            assertEquals(AppConstants.KEY_TERMINADA, inst2.getEstado());
            assertEquals(0, wallet1.getBalanceDisponible().compareTo(new BigDecimal("1800.00")));
            assertEquals(0, wallet2.getBalanceDisponible().compareTo(new BigDecimal("2900.00")));
            assertEquals(0, inst1.getCapitalReservado().compareTo(BigDecimal.ZERO));
            assertEquals(0, inst2.getCapitalReservado().compareTo(BigDecimal.ZERO));

            verify(runtimeCoordinator, times(1)).detenerEstrategia(id1);
            verify(runtimeCoordinator, times(1)).detenerEstrategia(id2);
            verify(ledgerRepo, atLeastOnce()).save(any(LedgerEntry.class));
        }
    }

    private Wallet wallet(Long walletId, String balanceReal, String balanceDisponible) {
        Wallet wallet = new Wallet();
        wallet.setId(walletId);
        wallet.setBalanceReal(new BigDecimal(balanceReal));
        wallet.setBalanceDisponible(new BigDecimal(balanceDisponible));
        wallet.setNombre("wallet-test");
        wallet.setUsuarioId(1L);
        return wallet;
    }

    private InstanciaEstrategia instanciaActiva(Long instanciaId, Long walletId, String capital, String risk) {
        InstanciaEstrategia instancia = InstanciaEstrategia.inicializar(
            "RSISMAStrategy",
            "N/A",
            "1h",
            List.of("BTCUSDT"),
            false,
            walletId,
            new BigDecimal(risk),
            new BigDecimal(capital)
        );
        instancia.setId(instanciaId);
        instancia.setEstado(AppConstants.KEY_ACTIVA);
        instancia.setCapitalComprometido(BigDecimal.ZERO);
        instancia.setRiesgoAbierto(BigDecimal.ZERO);
        return instancia;
    }

    private SignalDTO signal(String action, String symbol, String timeframe, String price, long timestamp) {
        SignalDTO signal = new SignalDTO();
        signal.setAction(action);
        signal.setSymbol(symbol);
        signal.setTimeframe(timeframe);
        signal.setPrice(new BigDecimal(price));
        signal.setTimestamp(timestamp);
        return signal;
    }
}