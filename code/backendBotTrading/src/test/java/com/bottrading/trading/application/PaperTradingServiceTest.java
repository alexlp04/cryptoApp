package com.bottrading.trading.application;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
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

import com.bottrading.strategy.domain.EstadoEstrategia;
import com.bottrading.strategy.domain.InstanciaEstrategia;
import com.bottrading.strategy.domain.InstanciaEstrategiaRepository;
import com.bottrading.trading.application.port.in.AccountingUseCase;
import com.bottrading.trading.application.port.out.PosicionRepositoryPort;
import com.bottrading.trading.application.port.out.TradeResultsPort;
import com.bottrading.trading.domain.Posicion;
import com.bottrading.trading.infrastructure.bridge.ProcessedSignalRegistry;
import com.bottrading.trading.infrastructure.bridge.SignalDTO;
import com.bottrading.trading.infrastructure.cache.StatsCache;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaperTradingServiceTest {

    @Mock
    private AccountingUseCase accountingService;

    @Mock
    private InstanciaEstrategiaRepository instanciaRepo;

    @Mock
    private PosicionRepositoryPort posicionRepo;

    @Mock
    private TradeResultsPort tradeResultsPort;

    @Mock
    private StatsCache statsCache;

    @Mock
    private ProcessedSignalRegistry signalRegistry;

    @InjectMocks
    private PaperTradingService paperTradingService;

    // ─────────────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────────────
    private InstanciaEstrategia crearInstanciaActivaTest(BigDecimal capitalReservado) {
        InstanciaEstrategia inst = InstanciaEstrategia.inicializar(
                "RSISMAStrategy", "modelo", "1h",
                List.of("BTCUSDT"), false, 1L,
                new BigDecimal("0.05"), capitalReservado);
        inst.setId(10L);
        inst.setEstado(EstadoEstrategia.ACTIVA);
        inst.setWalletAsociada(1L);
        inst.setCapitalReservado(capitalReservado);
        inst.setCapitalComprometido(BigDecimal.ZERO);
        inst.setRiesgoAbierto(BigDecimal.ZERO);
        return inst;
    }

    private SignalDTO crearSignalBUY() {
        SignalDTO s = new SignalDTO();
        s.setAction("BUY");
        s.setSymbol("BTCUSDT");
        s.setPrice(new BigDecimal("30000"));
        s.setTimeframe("1h");
        s.setTimestamp(System.currentTimeMillis());
        return s;
    }

    private SignalDTO crearSignalSELL() {
        SignalDTO s = new SignalDTO();
        s.setAction("SELL");
        s.setSymbol("BTCUSDT");
        s.setPrice(new BigDecimal("31000"));
        s.setTimeframe("1h");
        s.setTimestamp(System.currentTimeMillis());
        return s;
    }

    private Posicion crearPosicionAbiertaTest(InstanciaEstrategia instancia) {
        Posicion pos = new Posicion();
        pos.setInstancia(instancia);
        pos.setSimbolo("BTCUSDT");
        pos.setPrecioEntrada(new BigDecimal("30000"));
        pos.setMargenInvertido(new BigDecimal("50"));
        pos.setAbierta(true);
        return pos;
    }

    @BeforeEach
    void configurarDefaults() {
        when(posicionRepo.save(any(Posicion.class))).thenAnswer(inv -> inv.getArgument(0));
        when(statsCache.getStats(anyString(), anyString(), anyString())).thenReturn(Map.of());
        doNothing().when(statsCache).updateStats(anyString(), anyString(), anyString(), any());
        doNothing().when(tradeResultsPort).guardarTrade(
                anyString(), anyString(), anyString(), anyString(), any(), anyLong(), any());
        doNothing().when(tradeResultsPort).guardarStats(anyString(), anyString(), any(), anyBoolean());
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
            assertThat(paperTradingService, is(notNullValue()));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // onSignal() — validaciones previas
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("onSignal() — Enrutamiento y validación de señales")
    class OnSignalTests {

        @Test
        @DisplayName("✓ Debe lanzar excepción cuando la instancia no existe")
        void should_throw_when_instance_not_found() {
            when(instanciaRepo.findByIdWithLock(anyLong())).thenReturn(Optional.empty());

            assertThrows(RuntimeException.class,
                    () -> paperTradingService.onSignal(99L, crearSignalBUY()));
        }

        @Test
        @DisplayName("✓ Debe ignorar señal cuando la estrategia está DETENIDA")
        void should_ignore_signal_when_strategy_is_detenida() {
            InstanciaEstrategia inst = crearInstanciaActivaTest(new BigDecimal("500"));
            inst.setEstado(EstadoEstrategia.DETENIDA);
            when(instanciaRepo.findByIdWithLock(10L)).thenReturn(Optional.of(inst));

            paperTradingService.onSignal(10L, crearSignalBUY());

            verify(posicionRepo, never()).save(any());
            verify(accountingService, never()).commitCapital(anyLong(), any(), any());
        }

        @Test
        @DisplayName("✓ Debe ignorar señal cuando la estrategia está TERMINADA")
        void should_ignore_signal_when_strategy_is_terminada() {
            InstanciaEstrategia inst = crearInstanciaActivaTest(new BigDecimal("500"));
            inst.setEstado(EstadoEstrategia.TERMINADA);
            when(instanciaRepo.findByIdWithLock(10L)).thenReturn(Optional.of(inst));

            paperTradingService.onSignal(10L, crearSignalBUY());

            verify(posicionRepo, never()).save(any());
        }

        @Test
        @DisplayName("✓ Debe enrutar señal BUY al manejador de compra")
        void should_route_buy_signal_to_buy_handler() {
            InstanciaEstrategia inst = crearInstanciaActivaTest(new BigDecimal("500"));
            when(instanciaRepo.findByIdWithLock(10L)).thenReturn(Optional.of(inst));
            when(posicionRepo.existsByInstanciaAndSimboloAndAbiertaTrue(any(), anyString())).thenReturn(false);

            paperTradingService.onSignal(10L, crearSignalBUY());

            verify(accountingService, times(1)).commitCapital(anyLong(), any(), any());
        }

        @Test
        @DisplayName("✓ Debe enrutar señal SELL al manejador de venta")
        void should_route_sell_signal_to_sell_handler() {
            InstanciaEstrategia inst = crearInstanciaActivaTest(new BigDecimal("500"));
            Posicion pos = crearPosicionAbiertaTest(inst);
            when(instanciaRepo.findByIdWithLock(10L)).thenReturn(Optional.of(inst));
            when(posicionRepo.findByInstanciaAndSimboloAndAbiertaTrue(any(), anyString()))
                    .thenReturn(Optional.of(pos));

            paperTradingService.onSignal(10L, crearSignalSELL());

            verify(accountingService, times(1)).closeTrade(anyLong(), anyLong(), any(), any(), any());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // onSignal() — Idempotencia (C3)
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("onSignal() — Idempotencia de señales")
    class IdempotencyTests {

        /** Servicio con un registro de idempotencia REAL (no mock) para ejercer el dedup. */
        private PaperTradingService serviceConRegistroReal() {
            return new PaperTradingService(
                    accountingService, instanciaRepo, posicionRepo, tradeResultsPort, statsCache,
                    new ProcessedSignalRegistry());
        }

        @Test
        @DisplayName("✓ Debe aplicar una señal BUY solo una vez aunque se entregue dos veces")
        void should_apply_buy_only_once_on_duplicate_delivery() {
            PaperTradingService svc = serviceConRegistroReal();
            InstanciaEstrategia inst = crearInstanciaActivaTest(new BigDecimal("500"));
            when(instanciaRepo.findByIdWithLock(10L)).thenReturn(Optional.of(inst));
            when(posicionRepo.existsByInstanciaAndSimboloAndAbiertaTrue(any(), anyString())).thenReturn(false);

            SignalDTO sig = crearSignalBUY();
            svc.onSignal(10L, sig);
            svc.onSignal(10L, sig);   // reentrega idéntica

            verify(accountingService, times(1)).commitCapital(anyLong(), any(), any());
            verify(posicionRepo, times(1)).save(any());
        }

        @Test
        @DisplayName("✓ Debe reintentar si la primera aplicación falló (no se marcó como procesada)")
        void should_reprocess_when_first_application_failed() {
            PaperTradingService svc = serviceConRegistroReal();
            InstanciaEstrategia inst = crearInstanciaActivaTest(new BigDecimal("500"));
            when(instanciaRepo.findByIdWithLock(10L)).thenReturn(Optional.of(inst));
            when(posicionRepo.existsByInstanciaAndSimboloAndAbiertaTrue(any(), anyString())).thenReturn(false);
            doThrow(new RuntimeException("fallo transitorio"))
                    .doNothing()
                    .when(accountingService).commitCapital(anyLong(), any(), any());

            SignalDTO sig = crearSignalBUY();
            assertThrows(RuntimeException.class, () -> svc.onSignal(10L, sig));
            svc.onSignal(10L, sig);   // reintento tras fallo: debe volver a intentarlo

            verify(accountingService, times(2)).commitCapital(anyLong(), any(), any());
        }

        @Test
        @DisplayName("✓ Distinto timestamp genera distinta clave y se aplica de nuevo")
        void should_apply_again_for_different_timestamp() {
            PaperTradingService svc = serviceConRegistroReal();
            InstanciaEstrategia inst = crearInstanciaActivaTest(new BigDecimal("500"));
            when(instanciaRepo.findByIdWithLock(10L)).thenReturn(Optional.of(inst));
            when(posicionRepo.existsByInstanciaAndSimboloAndAbiertaTrue(any(), anyString())).thenReturn(false);

            SignalDTO s1 = crearSignalBUY();
            s1.setTimestamp(1000L);
            SignalDTO s2 = crearSignalBUY();
            s2.setTimestamp(2000L);
            svc.onSignal(10L, s1);
            svc.onSignal(10L, s2);

            verify(accountingService, times(2)).commitCapital(anyLong(), any(), any());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // handleBuy (vía onSignal)
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("handleBuy() — Apertura de posición larga")
    class HandleBuyTests {

        @Test
        @DisplayName("✓ Debe crear posición, comprometer capital y registrar en CSV")
        void should_open_position_commit_capital_and_log_csv() {
            InstanciaEstrategia inst = crearInstanciaActivaTest(new BigDecimal("500"));
            when(instanciaRepo.findByIdWithLock(10L)).thenReturn(Optional.of(inst));
            when(posicionRepo.existsByInstanciaAndSimboloAndAbiertaTrue(any(), anyString())).thenReturn(false);

            paperTradingService.onSignal(10L, crearSignalBUY());

            verify(accountingService, times(1)).commitCapital(
                    eq(10L), any(BigDecimal.class), any(BigDecimal.class));
            verify(posicionRepo, times(1)).save(any(Posicion.class));
            verify(tradeResultsPort, times(1)).guardarTrade(
                    anyString(), anyString(), eq("BTCUSDT"), eq("BUY"), any(), anyLong(), any());
        }

        @Test
        @DisplayName("✓ No debe abrir posición si ya existe una abierta para el mismo símbolo")
        void should_skip_buy_when_position_already_open() {
            InstanciaEstrategia inst = crearInstanciaActivaTest(new BigDecimal("500"));
            when(instanciaRepo.findByIdWithLock(10L)).thenReturn(Optional.of(inst));
            when(posicionRepo.existsByInstanciaAndSimboloAndAbiertaTrue(any(), eq("BTCUSDT"))).thenReturn(true);

            paperTradingService.onSignal(10L, crearSignalBUY());

            verify(accountingService, never()).commitCapital(anyLong(), any(), any());
            verify(posicionRepo, never()).save(any());
        }

        @Test
        @DisplayName("✓ No debe abrir posición cuando el capital reservado es cero")
        void should_skip_buy_when_no_reserved_capital() {
            InstanciaEstrategia inst = crearInstanciaActivaTest(BigDecimal.ZERO);
            when(instanciaRepo.findByIdWithLock(10L)).thenReturn(Optional.of(inst));
            when(posicionRepo.existsByInstanciaAndSimboloAndAbiertaTrue(any(), anyString())).thenReturn(false);

            paperTradingService.onSignal(10L, crearSignalBUY());

            verify(accountingService, never()).commitCapital(anyLong(), any(), any());
            verify(posicionRepo, never()).save(any());
        }

        @Test
        @DisplayName("✓ El monto a invertir es capitalReservado * riskPerTrade")
        void should_invest_correct_amount() {
            InstanciaEstrategia inst = crearInstanciaActivaTest(new BigDecimal("1000"));
            // riskPerTrade = 0.05 → montoAInvertir = 1000 * 0.05 = 50
            when(instanciaRepo.findByIdWithLock(10L)).thenReturn(Optional.of(inst));
            when(posicionRepo.existsByInstanciaAndSimboloAndAbiertaTrue(any(), anyString())).thenReturn(false);

            paperTradingService.onSignal(10L, crearSignalBUY());

            verify(accountingService, times(1)).commitCapital(
                    eq(10L), eq(new BigDecimal("50.00")), eq(new BigDecimal("0.05")));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // handleSell (vía onSignal)
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("handleSell() — Cierre de posición con PnL")
    class HandleSellTests {

        @Test
        @DisplayName("✓ Debe cerrar posición, calcular PnL y actualizar stats")
        void should_close_position_and_update_stats() {
            InstanciaEstrategia inst = crearInstanciaActivaTest(new BigDecimal("500"));
            Posicion pos = crearPosicionAbiertaTest(inst);
            when(instanciaRepo.findByIdWithLock(10L)).thenReturn(Optional.of(inst));
            when(posicionRepo.findByInstanciaAndSimboloAndAbiertaTrue(any(), eq("BTCUSDT")))
                    .thenReturn(Optional.of(pos));

            paperTradingService.onSignal(10L, crearSignalSELL()); // precio salida 31000 > entrada 30000

            verify(accountingService, times(1)).closeTrade(anyLong(), anyLong(), any(), any(), any());
            assertThat(pos.isAbierta(), is(false));
            verify(posicionRepo, times(1)).save(pos);
            verify(tradeResultsPort, times(1)).guardarTrade(
                    anyString(), anyString(), eq("BTCUSDT"), eq("SELL"), any(), anyLong(), any());
            verify(tradeResultsPort, times(1)).guardarStats(anyString(), anyString(), any(), eq(false));
        }

        @Test
        @DisplayName("✓ El PnL ganador debe ser positivo cuando precio salida > precio entrada")
        void should_calculate_positive_pnl_when_price_rises() {
            InstanciaEstrategia inst = crearInstanciaActivaTest(new BigDecimal("500"));
            Posicion pos = crearPosicionAbiertaTest(inst);  // entrada 30000, margen 50
            when(instanciaRepo.findByIdWithLock(10L)).thenReturn(Optional.of(inst));
            when(posicionRepo.findByInstanciaAndSimboloAndAbiertaTrue(any(), eq("BTCUSDT")))
                    .thenReturn(Optional.of(pos));

            // Precio salida = 31000 → multiplicador = 31000/30000 ≈ 1.03333
            // montoFinal = 50 * 1.03333... ≈ 51.666..., pnl ≈ 1.666...
            paperTradingService.onSignal(10L, crearSignalSELL());

            assertThat(pos.getPnl().compareTo(BigDecimal.ZERO) > 0, is(true));
        }

        @Test
        @DisplayName("✓ No debe hacer nada cuando no hay posición abierta para el símbolo")
        void should_do_nothing_when_no_open_position() {
            InstanciaEstrategia inst = crearInstanciaActivaTest(new BigDecimal("500"));
            when(instanciaRepo.findByIdWithLock(10L)).thenReturn(Optional.of(inst));
            when(posicionRepo.findByInstanciaAndSimboloAndAbiertaTrue(any(), anyString()))
                    .thenReturn(Optional.empty());

            paperTradingService.onSignal(10L, crearSignalSELL());

            verify(accountingService, never()).closeTrade(anyLong(), anyLong(), any(), any(), any());
            verify(posicionRepo, never()).save(any());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CONTRATO DE PUERTOS
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("Contrato de interfaz ProcessSignalUseCase")
    class ContratoPuertoEntradaTests {

        @Test
        @DisplayName("✓ PaperTradingService debe implementar ProcessSignalUseCase")
        void should_implement_processSignalUseCase_interface() {
            assertThat(paperTradingService instanceof
                    com.bottrading.trading.application.port.in.ProcessSignalUseCase,
                    is(true));
        }
    }
}
