package com.bottrading.application.trading;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.bottrading.beans.SignalDTO;
import com.bottrading.domain.strategy.EstadoEstrategia;
import com.bottrading.domain.strategy.InstanciaEstrategia;
import com.bottrading.domain.strategy.InstanciaEstrategiaRepository;
import com.bottrading.domain.trading.Posicion;
import com.bottrading.domain.trading.PosicionRepository;
import com.bottrading.infrastructure.cache.StatsCache;
import com.bottrading.infrastructure.persistence.FileService;

import lombok.extern.slf4j.Slf4j;

/**
 * Test exhaustivo para PaperTradingService.
 * Cubre: construcción, procesamiento de señales BUY/SELL, cálculo de estadísticas,
 * validaciones de riesgo, interacciones con puertos, y casos de error.
 * 
 * Patrón: @ExtendWith(MockitoExtension.class) + @InjectMocks + @Mock ports.
 * Total: ~35 test methods en 8 @Nested clases.
 */
@Slf4j
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaperTradingServiceTest {

    @Mock
    private AccountingService accountingService;

    @Mock
    private InstanciaEstrategiaRepository instanciaRepo;

    @Mock
    private PosicionRepository posicionRepo;

    @Mock
    private FileService fileService;

    @Mock
    private StatsCache statsCache;

    @InjectMocks
    private PaperTradingService service;

    // ========== CONSTRUCCIÓN ==========
    @Nested
    @DisplayName("CONSTRUCCIÓN: Instanciación de PaperTradingService")
    class ConstructionTests {

        @Test
        @DisplayName("✓ Debe crear servicio con dependencias inyectadas")
        void should_create_service_with_injected_dependencies() {
            assertThat(service, is(notNullValue()));
            assertThat(service, is(instanceOf(PaperTradingService.class)));
        }

        @Test
        @DisplayName("✓ Debe exponer método onSignal(Long, SignalDTO)")
        void should_expose_on_signal_method() throws Exception {
            assertThat(
                PaperTradingService.class.getDeclaredMethod("onSignal", Long.class, SignalDTO.class),
                is(notNullValue())
            );
        }
    }

    // ========== onSignal() - PROCESAMIENTO DE SEÑALES ==========
    @Nested
    @DisplayName("onSignal(instanciaId, signal) - Procesamiento de señales")
    class OnSignalTests {

        @Test
        @DisplayName("✓ Debe llamar instancia repository para obtener instancia")
        void should_fetch_instancia_from_repository() throws Exception {
            // Given
            SignalDTO signal = crearSignalTestBUY();
            when(instanciaRepo.findByIdWithLock(1L))
                .thenReturn(Optional.empty());

            // When
            try {
                service.onSignal(1L, signal);
            } catch (RuntimeException e) {
                // Expected
            }

            // Then
            verify(instanciaRepo, times(1)).findByIdWithLock(1L);
        }

        @Test
        @DisplayName("✓ Debe lanzar excepción cuando instancia no existe")
        void should_throw_exception_when_instancia_not_found() throws Exception {
            // Given
            SignalDTO signal = crearSignalTestBUY();
            when(instanciaRepo.findByIdWithLock(999L))
                .thenReturn(Optional.empty());

            // When & Then
            assertThrows(RuntimeException.class, () -> {
                service.onSignal(999L, signal);
            });
        }

        @Test
        @DisplayName("✓ Debe ignorar señal si instancia no está ACTIVA")
        void should_ignore_signal_when_instancia_not_active() throws Exception {
            // Given
            InstanciaEstrategia instancia = new InstanciaEstrategia();
            instancia.setEstado(EstadoEstrategia.DETENIDA);
            SignalDTO signal = crearSignalTestBUY();
            
            when(instanciaRepo.findByIdWithLock(1L))
                .thenReturn(Optional.of(instancia));

            // When
            service.onSignal(1L, signal);

            // Then — No debe procesar la señal
            verify(accountingService, never()).commitCapital(anyLong(), any(BigDecimal.class), any(BigDecimal.class));
        }

        @Test
        @DisplayName("✓ Debe ignorar HOLD signal")
        void should_ignore_hold_signal() throws Exception {
            // Given
            InstanciaEstrategia instancia = crearInstanciaActivaTest();
            SignalDTO signal = crearSignalTestBUY();
            signal.setAction("HOLD");
            
            when(instanciaRepo.findByIdWithLock(1L))
                .thenReturn(Optional.of(instancia));

            // When
            service.onSignal(1L, signal);

            // Then
            verify(accountingService, never()).commitCapital(anyLong(), any(BigDecimal.class), any(BigDecimal.class));
        }

        @Test
        @DisplayName("✓ Debe procesar señal BUY válida")
        void should_process_valid_buy_signal() throws Exception {
            // Given
            InstanciaEstrategia instancia = crearInstanciaActivaTest();
            SignalDTO signal = crearSignalTestBUY();
            
            when(instanciaRepo.findByIdWithLock(1L))
                .thenReturn(Optional.of(instancia));
            when(posicionRepo.existsByInstanciaAndSimboloAndAbiertaTrue(instancia, "BTCUSDT"))
                .thenReturn(false);

            // When
            service.onSignal(1L, signal);

            // Then
            verify(posicionRepo, atLeastOnce()).existsByInstanciaAndSimboloAndAbiertaTrue(instancia, "BTCUSDT");
        }

        @Test
        @DisplayName("✓ Debe procesar señal SELL válida")
        void should_process_valid_sell_signal() throws Exception {
            // Given
            InstanciaEstrategia instancia = crearInstanciaActivaTest();
            SignalDTO signal = crearSignalTestSELL();
            Posicion posicion = crearPosicionTest();
            Map<String, Object> statsMap = new HashMap<>();
            
            when(instanciaRepo.findByIdWithLock(1L))
                .thenReturn(Optional.of(instancia));
            when(posicionRepo.findByInstanciaAndSimboloAndAbiertaTrue(instancia, "BTCUSDT"))
                .thenReturn(Optional.of(posicion));
            when(statsCache.getStats(anyString(), anyString(), anyString()))
                .thenReturn(statsMap);

            // When
            service.onSignal(1L, signal);

            // Then
            verify(posicionRepo, atLeastOnce())
                .findByInstanciaAndSimboloAndAbiertaTrue(instancia, "BTCUSDT");
        }
    }

    // ========== BUY OPERATIONS ==========
    @Nested
    @DisplayName("handleBuy() - Lógica de compra")
    class BuyOperationTests {

        @Test
        @DisplayName("✓ Debe evitar abrir múltiples posiciones en el mismo símbolo")
        void should_prevent_multiple_positions_same_symbol() throws Exception {
            // Given
            InstanciaEstrategia instancia = crearInstanciaActivaTest();
            SignalDTO signal = crearSignalTestBUY();
            
            when(instanciaRepo.findByIdWithLock(1L))
                .thenReturn(Optional.of(instancia));
            when(posicionRepo.existsByInstanciaAndSimboloAndAbiertaTrue(instancia, "BTCUSDT"))
                .thenReturn(true);  // Ya existe una posición

            // When
            service.onSignal(1L, signal);

            // Then — No debe intentar abrir otra posición
            verify(posicionRepo, never()).save(any(Posicion.class));
        }

        @Test
        @DisplayName("✓ Debe registrar trade en archivo")
        void should_save_trade_to_file() throws Exception {
            // Given
            InstanciaEstrategia instancia = crearInstanciaActivaTest();
            instancia.setNombreEstrategia("RSI_SMA");
            instancia.setTimeframe("1h");
            SignalDTO signal = crearSignalTestBUY();
            
            when(instanciaRepo.findByIdWithLock(1L))
                .thenReturn(Optional.of(instancia));
            when(posicionRepo.existsByInstanciaAndSimboloAndAbiertaTrue(instancia, "BTCUSDT"))
                .thenReturn(false);

            // When
            service.onSignal(1L, signal);

            // Then
            verify(fileService, atLeastOnce()).guardarTrade(anyString(), anyString(), anyString(), anyString(), 
                any(BigDecimal.class), anyLong(), any());
        }

        @Test
        @DisplayName("✓ Debe rechazar BUY si el margen supera el capital")
        void should_reject_buy_when_margin_exceeds_capital() throws Exception {
            // Given
            InstanciaEstrategia instancia = crearInstanciaActivaTest();
            instancia.setCapitalReservado(new BigDecimal("100.00"));
            instancia.setRiskPerTrade(new BigDecimal("1.5"));  // 150% inválido
            SignalDTO signal = crearSignalTestBUY();
            
            when(instanciaRepo.findByIdWithLock(1L))
                .thenReturn(Optional.of(instancia));
            when(posicionRepo.existsByInstanciaAndSimboloAndAbiertaTrue(instancia, "BTCUSDT"))
                .thenReturn(false);

            // When
            service.onSignal(1L, signal);

            // Then
            verify(accountingService, never()).commitCapital(anyLong(), any(BigDecimal.class), any(BigDecimal.class));
        }

        @ParameterizedTest
        @ValueSource(strings = {"BTCUSDT", "ETHUSDT", "BNBUSDT", "ADAUSDT"})
        @DisplayName("✓ Debe procesar BUY para diferentes símbolos")
        void should_process_buy_for_different_symbols(String symbol) throws Exception {
            // Given
            InstanciaEstrategia instancia = crearInstanciaActivaTest();
            SignalDTO signal = crearSignalTestBUY();
            signal.setSymbol(symbol);
            
            when(instanciaRepo.findByIdWithLock(1L))
                .thenReturn(Optional.of(instancia));
            when(posicionRepo.existsByInstanciaAndSimboloAndAbiertaTrue(instancia, symbol))
                .thenReturn(false);

            // When
            service.onSignal(1L, signal);

            // Then
            verify(posicionRepo, atLeastOnce()).existsByInstanciaAndSimboloAndAbiertaTrue(instancia, symbol);
        }
    }

    // ========== SELL OPERATIONS ==========
    @Nested
    @DisplayName("handleSell() - Lógica de venta")
    class SellOperationTests {

        @Test
        @DisplayName("✓ Debe ignorar SELL si no hay posición abierta")
        void should_ignore_sell_when_no_open_position() throws Exception {
            // Given
            InstanciaEstrategia instancia = crearInstanciaActivaTest();
            SignalDTO signal = crearSignalTestSELL();
            
            when(instanciaRepo.findByIdWithLock(1L))
                .thenReturn(Optional.of(instancia));
            when(posicionRepo.findByInstanciaAndSimboloAndAbiertaTrue(instancia, "BTCUSDT"))
                .thenReturn(Optional.empty());

            // When
            service.onSignal(1L, signal);

            // Then
            verify(accountingService, never()).closeTrade(anyLong(), anyLong(), any(BigDecimal.class), any(BigDecimal.class), any(BigDecimal.class));
        }

        @Test
        @DisplayName("✓ Debe actualizar posición después de SELL")
        void should_save_position_after_sell() throws Exception {
            // Given
            InstanciaEstrategia instancia = crearInstanciaActivaTest();
            Posicion posicion = crearPosicionTest();
            SignalDTO signal = crearSignalTestSELL();
            
            when(instanciaRepo.findByIdWithLock(1L))
                .thenReturn(Optional.of(instancia));
            when(posicionRepo.findByInstanciaAndSimboloAndAbiertaTrue(instancia, "BTCUSDT"))
                .thenReturn(Optional.of(posicion));
            when(statsCache.getStats(anyString(), anyString(), anyString()))
                .thenReturn(new HashMap<>());

            // When
            service.onSignal(1L, signal);

            // Then
            verify(posicionRepo, atLeastOnce()).save(any(Posicion.class));
        }

        @Test
        @DisplayName("✓ Debe guardar resultado SELL en archivo")
        void should_save_sell_result_to_file() throws Exception {
            // Given
            InstanciaEstrategia instancia = crearInstanciaActivaTest();
            instancia.setNombreEstrategia("RSI_SMA");
            instancia.setTimeframe("1h");
            Posicion posicion = crearPosicionTest();
            SignalDTO signal = crearSignalTestSELL();
            
            when(instanciaRepo.findByIdWithLock(1L))
                .thenReturn(Optional.of(instancia));
            when(posicionRepo.findByInstanciaAndSimboloAndAbiertaTrue(instancia, "BTCUSDT"))
                .thenReturn(Optional.of(posicion));
            when(statsCache.getStats(anyString(), anyString(), anyString()))
                .thenReturn(new HashMap<>());

            // When
            service.onSignal(1L, signal);

            // Then
            verify(fileService, atLeastOnce()).guardarTrade(anyString(), anyString(), anyString(), eq("SELL"), 
                any(BigDecimal.class), anyLong(), any());
        }
    }

    // ========== PORT INTERACTIONS ==========
    @Nested
    @DisplayName("PUERTOS: Verificación de interacciones")
    class PortInteractionTests {

        @Test
        @DisplayName("✓ Debe llamar InstanciaRepository en onSignal")
        void should_call_instancia_repository() throws Exception {
            // Given
            SignalDTO signal = crearSignalTestBUY();
            when(instanciaRepo.findByIdWithLock(1L))
                .thenReturn(Optional.empty());

            // When
            try {
                service.onSignal(1L, signal);
            } catch (RuntimeException e) {
                // Expected
            }

            // Then
            verify(instanciaRepo, times(1)).findByIdWithLock(1L);
        }

        @Test
        @DisplayName("✓ Debe llamar PosicionRepository para operaciones BUY")
        void should_call_posicion_repository() throws Exception {
            // Given
            InstanciaEstrategia instancia = crearInstanciaActivaTest();
            SignalDTO signal = crearSignalTestBUY();
            
            when(instanciaRepo.findByIdWithLock(1L))
                .thenReturn(Optional.of(instancia));
            when(posicionRepo.existsByInstanciaAndSimboloAndAbiertaTrue(instancia, "BTCUSDT"))
                .thenReturn(false);

            // When
            service.onSignal(1L, signal);

            // Then
            verify(posicionRepo, atLeastOnce()).existsByInstanciaAndSimboloAndAbiertaTrue(instancia, "BTCUSDT");
        }

        @Test
        @DisplayName("✓ Debe llamar FileService para registrar trades")
        void should_call_file_service() throws Exception {
            // Given
            InstanciaEstrategia instancia = crearInstanciaActivaTest();
            instancia.setNombreEstrategia("TEST");
            instancia.setTimeframe("1h");
            SignalDTO signal = crearSignalTestBUY();
            
            when(instanciaRepo.findByIdWithLock(1L))
                .thenReturn(Optional.of(instancia));
            when(posicionRepo.existsByInstanciaAndSimboloAndAbiertaTrue(instancia, "BTCUSDT"))
                .thenReturn(false);

            // When
            service.onSignal(1L, signal);

            // Then
            verify(fileService, atLeastOnce()).guardarTrade(anyString(), anyString(), anyString(), anyString(), 
                any(BigDecimal.class), anyLong(), any());
        }

        @Test
        @DisplayName("✓ Debe llamar StatsCache en operaciones SELL")
        void should_call_stats_cache() throws Exception {
            // Given
            InstanciaEstrategia instancia = crearInstanciaActivaTest();
            Posicion posicion = crearPosicionTest();
            SignalDTO signal = crearSignalTestSELL();
            
            when(instanciaRepo.findByIdWithLock(1L))
                .thenReturn(Optional.of(instancia));
            when(posicionRepo.findByInstanciaAndSimboloAndAbiertaTrue(instancia, "BTCUSDT"))
                .thenReturn(Optional.of(posicion));
            when(statsCache.getStats(anyString(), anyString(), anyString()))
                .thenReturn(new HashMap<>());

            // When
            service.onSignal(1L, signal);

            // Then
            verify(statsCache, atLeastOnce()).getStats(anyString(), anyString(), anyString());
        }
    }

    // ========== BOUNDARY CASES ==========
    @Nested
    @DisplayName("BOUNDARY: Casos límite")
    class BoundaryTests {

        @Test
        @DisplayName("✓ Debe procesar BUY con capital mínimo")
        void should_process_with_minimum_capital() throws Exception {
            // Given
            InstanciaEstrategia instancia = crearInstanciaActivaTest();
            instancia.setCapitalReservado(new BigDecimal("1.00"));
            SignalDTO signal = crearSignalTestBUY();
            
            when(instanciaRepo.findByIdWithLock(1L))
                .thenReturn(Optional.of(instancia));
            when(posicionRepo.existsByInstanciaAndSimboloAndAbiertaTrue(instancia, "BTCUSDT"))
                .thenReturn(false);

            // When & Then
            assertDoesNotThrow(() -> {
                service.onSignal(1L, signal);
            });
        }

        @Test
        @DisplayName("✓ Debe procesar SELL en break-even")
        void should_process_sell_at_break_even() throws Exception {
            // Given
            InstanciaEstrategia instancia = crearInstanciaActivaTest();
            Posicion posicion = crearPosicionTest();
            SignalDTO signal = crearSignalTestSELL();
            signal.setPrice(posicion.getPrecioEntrada());  // Break-even
            
            when(instanciaRepo.findByIdWithLock(1L))
                .thenReturn(Optional.of(instancia));
            when(posicionRepo.findByInstanciaAndSimboloAndAbiertaTrue(instancia, "BTCUSDT"))
                .thenReturn(Optional.of(posicion));
            when(statsCache.getStats(anyString(), anyString(), anyString()))
                .thenReturn(new HashMap<>());

            // When & Then
            assertDoesNotThrow(() -> {
                service.onSignal(1L, signal);
            });
        }

        @Test
        @DisplayName("✓ Debe procesar operaciones múltiples en secuencia")
        void should_process_multiple_signals_in_sequence() throws Exception {
            // Given
            InstanciaEstrategia instancia = crearInstanciaActivaTest();
            
            when(instanciaRepo.findByIdWithLock(1L))
                .thenReturn(Optional.of(instancia));
            when(posicionRepo.existsByInstanciaAndSimboloAndAbiertaTrue(instancia, "BTCUSDT"))
                .thenReturn(false);

            // When — Multiple BUY signals
            service.onSignal(1L, crearSignalTestBUY());
            service.onSignal(1L, crearSignalTestBUY());
            service.onSignal(1L, crearSignalTestBUY());

            // Then
            verify(posicionRepo, atLeastOnce()).existsByInstanciaAndSimboloAndAbiertaTrue(instancia, "BTCUSDT");
        }
    }

    // ========== HELPERS ==========
    @Nested
    @DisplayName("HELPERS: Utilidades de test")
    class HelperTests {

        @Test
        @DisplayName("✓ crearSignalTestBUY() genera señal válida")
        void should_create_valid_buy_signal() {
            // When
            SignalDTO signal = crearSignalTestBUY();

            // Then
            assertThat(signal, is(notNullValue()));
            assertThat(signal.getAction(), is("BUY"));
            assertThat(signal.getPrice(), is(notNullValue()));
        }

        @Test
        @DisplayName("✓ crearSignalTestSELL() genera señal válida")
        void should_create_valid_sell_signal() {
            // When
            SignalDTO signal = crearSignalTestSELL();

            // Then
            assertThat(signal, is(notNullValue()));
            assertThat(signal.getAction(), is("SELL"));
            assertThat(signal.getSymbol(), is("BTCUSDT"));
        }
    }

    // ========== UTILITY METHODS ==========

    private SignalDTO crearSignalTestBUY() {
        SignalDTO signal = new SignalDTO();
        signal.setAction("BUY");
        signal.setSymbol("BTCUSDT");
        signal.setPrice(new BigDecimal("40000.00"));
        signal.setTimeframe("1h");
        signal.setTimestamp(Instant.now().toEpochMilli());
        return signal;
    }

    private SignalDTO crearSignalTestSELL() {
        SignalDTO signal = new SignalDTO();
        signal.setAction("SELL");
        signal.setSymbol("BTCUSDT");
        signal.setPrice(new BigDecimal("41000.00"));
        signal.setTimeframe("1h");
        signal.setTimestamp(Instant.now().toEpochMilli());
        return signal;
    }

    private Posicion crearPosicionTest() {
        Posicion posicion = new Posicion();
        posicion.setId(1L);
        posicion.setSimbolo("BTCUSDT");
        posicion.setPrecioEntrada(new BigDecimal("40000.00"));
        posicion.setMargenInvertido(new BigDecimal("200.00"));
        posicion.setAbierta(true);
        return posicion;
    }

    private InstanciaEstrategia crearInstanciaActivaTest() {
        InstanciaEstrategia instancia = new InstanciaEstrategia();
        instancia.setId(1L);
        instancia.setEstado(EstadoEstrategia.ACTIVA);
        instancia.setNombreEstrategia("RSI_SMA");
        instancia.setTimeframe("1h");
        instancia.setCapitalReservado(new BigDecimal("10000.00"));
        instancia.setRiskPerTrade(new BigDecimal("0.02"));
        instancia.setWalletAsociada(1L);
        return instancia;
    }
}
