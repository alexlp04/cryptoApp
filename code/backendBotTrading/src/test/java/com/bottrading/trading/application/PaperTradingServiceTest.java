package com.bottrading.trading.application;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
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
import com.bottrading.trading.domain.Lado;
import com.bottrading.trading.domain.Posicion;
import com.bottrading.trading.infrastructure.bridge.ProcessedSignalRegistry;
import com.bottrading.trading.infrastructure.bridge.SignalDTO;

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
        s.setStopLoss(new BigDecimal("29000"));
        s.setTakeProfit(new BigDecimal("33000"));
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
            verify(accountingService, never()).commitCapital(anyLong(), any(), any(), any());
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

            verify(accountingService, times(1)).commitCapital(anyLong(), any(), any(), any());
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

            verify(accountingService, times(1)).closeTrade(anyLong(), anyLong(), any(), any(), any(), any());
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
                    accountingService, instanciaRepo, posicionRepo,
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

            verify(accountingService, times(1)).commitCapital(anyLong(), any(), any(), any());
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
                    .when(accountingService).commitCapital(anyLong(), any(), any(), any());

            SignalDTO sig = crearSignalBUY();
            assertThrows(RuntimeException.class, () -> svc.onSignal(10L, sig));
            svc.onSignal(10L, sig);   // reintento tras fallo: debe volver a intentarlo

            verify(accountingService, times(2)).commitCapital(anyLong(), any(), any(), any());
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

            verify(accountingService, times(2)).commitCapital(anyLong(), any(), any(), any());
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
                    eq(10L), any(BigDecimal.class), any(BigDecimal.class), any());
            verify(posicionRepo, times(1)).save(any(Posicion.class));
        }

        @Test
        @DisplayName("✓ No debe abrir posición si ya existe una abierta para el mismo símbolo")
        void should_skip_buy_when_position_already_open() {
            InstanciaEstrategia inst = crearInstanciaActivaTest(new BigDecimal("500"));
            when(instanciaRepo.findByIdWithLock(10L)).thenReturn(Optional.of(inst));
            when(posicionRepo.existsByInstanciaAndSimboloAndAbiertaTrue(any(), eq("BTCUSDT"))).thenReturn(true);

            paperTradingService.onSignal(10L, crearSignalBUY());

            verify(accountingService, never()).commitCapital(anyLong(), any(), any(), any());
            verify(posicionRepo, never()).save(any());
        }

        @Test
        @DisplayName("✓ No debe abrir posición cuando el capital reservado es cero")
        void should_skip_buy_when_no_reserved_capital() {
            InstanciaEstrategia inst = crearInstanciaActivaTest(BigDecimal.ZERO);
            when(instanciaRepo.findByIdWithLock(10L)).thenReturn(Optional.of(inst));
            when(posicionRepo.existsByInstanciaAndSimboloAndAbiertaTrue(any(), anyString())).thenReturn(false);

            paperTradingService.onSignal(10L, crearSignalBUY());

            verify(accountingService, never()).commitCapital(anyLong(), any(), any(), any());
            verify(posicionRepo, never()).save(any());
        }

        @Test
        @DisplayName("✓ El tamaño arriesga equity * riskPerTrade hasta el stop (#99)")
        void should_size_the_position_by_risk_to_the_stop() {
            InstanciaEstrategia inst = crearInstanciaActivaTest(new BigDecimal("1000"));
            inst.setRiskPerTrade(new BigDecimal("0.01")); // 10 de riesgo
            when(instanciaRepo.findByIdWithLock(10L)).thenReturn(Optional.of(inst));
            when(posicionRepo.existsByInstanciaAndSimboloAndAbiertaTrue(any(), anyString())).thenReturn(false);
            SignalDTO s = crearSignalBUY();
            s.setStopLoss(new BigDecimal("15000")); // stop lejano: manda el riesgo, no el tope del 25 %

            paperTradingService.onSignal(10L, s);

            // entrada con slippage 30015; riesgo ≈ 10 → el margen es ≈ 20 y lo arriesgado ≈ 10
            verify(accountingService).commitCapital(eq(10L),
                    argThat(m -> m.subtract(new BigDecimal("19.99")).abs().compareTo(new BigDecimal("0.01")) < 0),
                    argThat(r -> r.subtract(BigDecimal.TEN).abs().compareTo(new BigDecimal("0.01")) < 0), any());
        }

        @Test
        @DisplayName("✓ Un stop muy estrecho lo limita el tope del 25 % del equity (#99)")
        void should_cap_the_position_at_a_quarter_of_equity() {
            InstanciaEstrategia inst = crearInstanciaActivaTest(new BigDecimal("1000"));
            when(instanciaRepo.findByIdWithLock(10L)).thenReturn(Optional.of(inst));
            when(posicionRepo.existsByInstanciaAndSimboloAndAbiertaTrue(any(), anyString())).thenReturn(false);
            SignalDTO s = crearSignalBUY();
            s.setStopLoss(new BigDecimal("29990"));

            paperTradingService.onSignal(10L, s);

            verify(accountingService).commitCapital(eq(10L),
                    argThat(m -> m.compareTo(new BigDecimal("250")) <= 0 && m.compareTo(new BigDecimal("249")) > 0),
                    any(), any());
        }

        @Test
        @DisplayName("✓ Sin stop válido en la señal no se compra (#99)")
        void should_reject_buy_without_a_valid_stop() {
            InstanciaEstrategia inst = crearInstanciaActivaTest(new BigDecimal("1000"));
            when(instanciaRepo.findByIdWithLock(10L)).thenReturn(Optional.of(inst));
            when(posicionRepo.existsByInstanciaAndSimboloAndAbiertaTrue(any(), anyString())).thenReturn(false);
            SignalDTO sinStop = crearSignalBUY();
            sinStop.setStopLoss(null);
            SignalDTO stopArriba = crearSignalBUY();
            stopArriba.setStopLoss(new BigDecimal("31000"));
            stopArriba.setTimestamp(1L);

            paperTradingService.onSignal(10L, sinStop);
            paperTradingService.onSignal(10L, stopArriba);

            verify(accountingService, never()).commitCapital(anyLong(), any(), any(), any());
            verify(posicionRepo, never()).save(any());
        }

        @Test
        @DisplayName("✓ No se abre si el riesgo abierto superaría el 6 % del equity (#99)")
        void should_reject_buy_beyond_the_open_risk_limit() {
            InstanciaEstrategia inst = crearInstanciaActivaTest(new BigDecimal("1000"));
            inst.setRiesgoAbierto(new BigDecimal("59")); // el nuevo trade añade ≈ 8 y el límite es 60
            when(instanciaRepo.findByIdWithLock(10L)).thenReturn(Optional.of(inst));
            when(posicionRepo.existsByInstanciaAndSimboloAndAbiertaTrue(any(), anyString())).thenReturn(false);

            paperTradingService.onSignal(10L, crearSignalBUY());

            verify(accountingService, never()).commitCapital(anyLong(), any(), any(), any());
        }

        @Test
        @DisplayName("✓ Una estrategia real no ejecuta señales: solo se simula (#99)")
        void should_ignore_signals_of_real_strategies() {
            InstanciaEstrategia inst = crearInstanciaActivaTest(new BigDecimal("1000"));
            inst.setEsReal(true);
            when(instanciaRepo.findByIdWithLock(10L)).thenReturn(Optional.of(inst));

            paperTradingService.onSignal(10L, crearSignalBUY());

            verify(accountingService, never()).commitCapital(anyLong(), any(), any(), any());
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

            verify(accountingService, times(1)).closeTrade(anyLong(), anyLong(), any(), any(), any(), any());
            assertThat(pos.isAbierta(), is(false));
            verify(posicionRepo, times(1)).save(pos);
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
        @DisplayName("✓ El PnL descuenta slippage y comisiones, y libera el riesgo de la posición (#99)")
        void should_close_net_of_costs_and_release_the_position_risk() {
            InstanciaEstrategia inst = crearInstanciaActivaTest(new BigDecimal("500"));
            Posicion pos = crearPosicionAbiertaTest(inst);
            pos.setUnidades(new BigDecimal("0.01"));
            pos.setPrecioEntrada(new BigDecimal("30015"));
            pos.setRiesgo(new BigDecimal("10.15"));
            when(instanciaRepo.findByIdWithLock(10L)).thenReturn(Optional.of(inst));
            when(posicionRepo.findByInstanciaAndSimboloAndAbiertaTrue(any(), eq("BTCUSDT")))
                    .thenReturn(Optional.of(pos));

            paperTradingService.onSignal(10L, crearSignalSELL()); // 31000 → sale a 30984,5

            // bruto = 0.01 * (30984.5 - 30015) = 9.695; comisión = 0.001 * 0.01 * (30015 + 30984.5) = 0.609995
            verify(accountingService).closeTrade(any(), eq(10L), any(),
                    eq(new BigDecimal("9.08500500")), eq(new BigDecimal("10.15")), any());
            assertThat(pos.getComisiones(), is(new BigDecimal("0.60999500")));
        }

        @Test
        @DisplayName("✓ Un objetivo se llena a su precio, sin slippage (#99)")
        void should_fill_a_target_without_slippage() {
            InstanciaEstrategia inst = crearInstanciaActivaTest(new BigDecimal("500"));
            Posicion pos = crearPosicionAbiertaTest(inst);
            when(instanciaRepo.findByIdWithLock(10L)).thenReturn(Optional.of(inst));
            when(posicionRepo.findByInstanciaAndSimboloAndAbiertaTrue(any(), eq("BTCUSDT")))
                    .thenReturn(Optional.of(pos));
            SignalDTO objetivo = crearSignalSELL();
            objetivo.setReason("TARGET");

            paperTradingService.onSignal(10L, objetivo);

            assertThat(pos.getPrecioSalida(), is(new BigDecimal("31000.00000000")));
        }

        @Test
        @DisplayName("✓ No debe hacer nada cuando no hay posición abierta para el símbolo")
        void should_do_nothing_when_no_open_position() {
            InstanciaEstrategia inst = crearInstanciaActivaTest(new BigDecimal("500"));
            when(instanciaRepo.findByIdWithLock(10L)).thenReturn(Optional.of(inst));
            when(posicionRepo.findByInstanciaAndSimboloAndAbiertaTrue(any(), anyString()))
                    .thenReturn(Optional.empty());

            paperTradingService.onSignal(10L, crearSignalSELL());

            verify(accountingService, never()).closeTrade(anyLong(), anyLong(), any(), any(), any(), any());
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

    // ─────────────────────────────────────────────────────────────────────────
    // Señales no operables (#70) y PnL con precios baratos (#56)
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("Señales inválidas y cálculo de PnL")
    class SenalesInvalidasYPnlTests {

        @Test
        @DisplayName("✓ Un símbolo que la estrategia no tiene configurado se descarta")
        void foreign_symbol_is_discarded() {
            InstanciaEstrategia inst = crearInstanciaActivaTest(new BigDecimal("1000"));
            when(instanciaRepo.findByIdWithLock(10L)).thenReturn(Optional.of(inst));
            SignalDTO s = crearSignalBUY();
            s.setSymbol("DOGEUSDT");

            paperTradingService.onSignal(10L, s);

            verify(accountingService, never()).commitCapital(anyLong(), any(), any(), any());
        }

        @Test
        @DisplayName("✓ Un timeframe distinto del configurado se descarta")
        void other_timeframe_is_discarded() {
            InstanciaEstrategia inst = crearInstanciaActivaTest(new BigDecimal("1000"));
            when(instanciaRepo.findByIdWithLock(10L)).thenReturn(Optional.of(inst));
            SignalDTO s = crearSignalBUY();
            s.setTimeframe("4h");

            paperTradingService.onSignal(10L, s);

            verify(accountingService, never()).commitCapital(anyLong(), any(), any(), any());
        }

        @Test
        @DisplayName("✓ Precio nulo o 0 no abre posición")
        void non_positive_price_is_discarded() {
            InstanciaEstrategia inst = crearInstanciaActivaTest(new BigDecimal("1000"));
            when(instanciaRepo.findByIdWithLock(10L)).thenReturn(Optional.of(inst));
            SignalDTO s = crearSignalBUY();
            s.setPrice(BigDecimal.ZERO);

            paperTradingService.onSignal(10L, s);

            verify(accountingService, never()).commitCapital(anyLong(), any(), any(), any());
        }

        @Test
        @DisplayName("✓ El PnL de un activo barato usa el precio exacto y una sola división")
        void pnl_of_cheap_asset_is_exact() {
            InstanciaEstrategia inst = crearInstanciaActivaTest(new BigDecimal("1000"));
            when(instanciaRepo.findByIdWithLock(10L)).thenReturn(Optional.of(inst));
            com.bottrading.trading.domain.Posicion pos = new com.bottrading.trading.domain.Posicion();
            pos.setAbierta(true);
            pos.setPrecioEntrada(new BigDecimal("0.62340000"));
            pos.setMargenInvertido(new BigDecimal("50"));
            when(posicionRepo.findByInstanciaAndSimboloAndAbiertaTrue(any(), anyString())).thenReturn(Optional.of(pos));
            SignalDTO s = crearSignalSELL();
            s.setPrice(new BigDecimal("0.7000"));

            paperTradingService.onSignal(10L, s);

            verify(accountingService).closeTrade(any(), eq(10L), eq(new BigDecimal("50")),
                    eq(new BigDecimal("6.11565608")), any(), any());
        }

        @Test
        @DisplayName("✓ Una posición heredada con precio de entrada 0 se cierra con PnL 0 en vez de lanzar")
        void corrupt_entry_price_closes_with_zero_pnl() {
            InstanciaEstrategia inst = crearInstanciaActivaTest(new BigDecimal("1000"));
            when(instanciaRepo.findByIdWithLock(10L)).thenReturn(Optional.of(inst));
            com.bottrading.trading.domain.Posicion pos = new com.bottrading.trading.domain.Posicion();
            pos.setAbierta(true);
            pos.setPrecioEntrada(BigDecimal.ZERO);
            pos.setMargenInvertido(new BigDecimal("50"));
            when(posicionRepo.findByInstanciaAndSimboloAndAbiertaTrue(any(), anyString())).thenReturn(Optional.of(pos));

            paperTradingService.onSignal(10L, crearSignalSELL());

            verify(accountingService).closeTrade(any(), eq(10L), eq(new BigDecimal("50")),
                    argThat(p -> p.signum() == 0), any(), any());
        }
    }


    // ─────────────────────────────────────────────────────────────────────────
    // CORTOS
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("CORTOS: SHORT abre y COVER cierra")
    class ShortTests {

        private SignalDTO crearSignalSHORT() {
            SignalDTO s = crearSignalBUY();
            s.setAction("SHORT");
            s.setStopLoss(new BigDecimal("31000"));
            s.setTakeProfit(new BigDecimal("27000"));
            return s;
        }

        private SignalDTO crearSignalCOVER(String precio) {
            SignalDTO s = crearSignalSELL();
            s.setAction("COVER");
            s.setPrice(new BigDecimal(precio));
            return s;
        }

        @Test
        @DisplayName("✓ SHORT vende con slippage en contra, guarda el lado y el riesgo hasta el stop")
        void should_open_a_short_selling_below_the_signal_price() {
            InstanciaEstrategia inst = crearInstanciaActivaTest(new BigDecimal("1000"));
            when(instanciaRepo.findByIdWithLock(10L)).thenReturn(Optional.of(inst));
            when(posicionRepo.existsByInstanciaAndSimboloAndAbiertaTrue(any(), anyString())).thenReturn(false);

            paperTradingService.onSignal(10L, crearSignalSHORT());

            verify(posicionRepo).save(argThat(p -> p.getLado() == Lado.SHORT
                    && p.getPrecioEntrada().compareTo(new BigDecimal("29985")) == 0)); // 30000 - 0,05 %
            verify(accountingService).commitCapital(eq(10L), any(), any(), any());
        }

        @Test
        @DisplayName("✗ Un SHORT con el stop por debajo de la entrada se rechaza")
        void should_reject_a_short_whose_stop_is_below_the_entry() {
            InstanciaEstrategia inst = crearInstanciaActivaTest(new BigDecimal("1000"));
            when(instanciaRepo.findByIdWithLock(10L)).thenReturn(Optional.of(inst));
            when(posicionRepo.existsByInstanciaAndSimboloAndAbiertaTrue(any(), anyString())).thenReturn(false);
            SignalDTO s = crearSignalSHORT();
            s.setStopLoss(new BigDecimal("29000"));

            paperTradingService.onSignal(10L, s);

            verify(posicionRepo, never()).save(any());
        }

        @Test
        @DisplayName("✓ COVER gana lo que baja el precio, neto de slippage y comisiones")
        void should_cover_a_short_with_profit_when_the_price_falls() {
            InstanciaEstrategia inst = crearInstanciaActivaTest(new BigDecimal("500"));
            Posicion pos = crearPosicionAbiertaTest(inst);
            pos.setLado(Lado.SHORT);
            pos.setUnidades(new BigDecimal("0.01"));
            pos.setPrecioEntrada(new BigDecimal("29985"));
            pos.setRiesgo(new BigDecimal("10"));
            when(instanciaRepo.findByIdWithLock(10L)).thenReturn(Optional.of(inst));
            when(posicionRepo.findByInstanciaAndSimboloAndAbiertaTrue(any(), eq("BTCUSDT")))
                    .thenReturn(Optional.of(pos));

            paperTradingService.onSignal(10L, crearSignalCOVER("29000")); // compra a 29014,5

            // bruto = 0.01 * (29985 - 29014.5) = 9.705; comisión = 0.001 * 0.01 * (29985 + 29014.5) = 0.589995
            verify(accountingService).closeTrade(any(), eq(10L), any(),
                    eq(new BigDecimal("9.11500500")), eq(new BigDecimal("10")), any());
        }

        @Test
        @DisplayName("✓ El objetivo de un corto se cubre a su precio, sin slippage")
        void should_cover_a_target_without_slippage() {
            InstanciaEstrategia inst = crearInstanciaActivaTest(new BigDecimal("500"));
            Posicion pos = crearPosicionAbiertaTest(inst);
            pos.setLado(Lado.SHORT);
            when(instanciaRepo.findByIdWithLock(10L)).thenReturn(Optional.of(inst));
            when(posicionRepo.findByInstanciaAndSimboloAndAbiertaTrue(any(), eq("BTCUSDT")))
                    .thenReturn(Optional.of(pos));
            SignalDTO objetivo = crearSignalCOVER("27000");
            objetivo.setReason("TARGET");

            paperTradingService.onSignal(10L, objetivo);

            assertThat(pos.getPrecioSalida(), is(new BigDecimal("27000.00000000")));
        }

        @Test
        @DisplayName("✓ SELL no cierra un corto y COVER no cierra un largo")
        void should_not_close_a_position_of_the_other_side() {
            InstanciaEstrategia inst = crearInstanciaActivaTest(new BigDecimal("500"));
            Posicion corto = crearPosicionAbiertaTest(inst);
            corto.setLado(Lado.SHORT);
            when(instanciaRepo.findByIdWithLock(10L)).thenReturn(Optional.of(inst));
            when(posicionRepo.findByInstanciaAndSimboloAndAbiertaTrue(any(), eq("BTCUSDT")))
                    .thenReturn(Optional.of(corto));

            paperTradingService.onSignal(10L, crearSignalSELL());

            verify(accountingService, never()).closeTrade(any(), any(), any(), any(), any(), any());
            assertThat(corto.isAbierta(), is(true));
        }
    }
}
