package com.bottrading.interfaces.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.bottrading.interfaces.api.ApiViews.Pnl;
import com.bottrading.interfaces.api.ApiViews.Status;
import com.bottrading.strategy.application.port.in.StrategyLifecycleUseCase;
import com.bottrading.strategy.domain.EstadoEstrategia;
import com.bottrading.strategy.domain.InstanciaEstrategia;
import com.bottrading.strategy.domain.InstanciaEstrategiaRepository;
import com.bottrading.trading.domain.LedgerRepository;
import com.bottrading.trading.domain.Posicion;
import com.bottrading.trading.domain.PosicionRepository;
import com.bottrading.wallet.infrastructure.persistence.WalletRepository;

@DisplayName("API de solo lectura: consultas")
class ApiQueryServiceTest {

    private InstanciaEstrategiaRepository instanciaRepo;
    private PosicionRepository posicionRepo;
    private StrategyLifecycleUseCase lifecycle;
    private ApiQueryService service;

    @BeforeEach
    void setUp() {
        instanciaRepo = mock(InstanciaEstrategiaRepository.class);
        posicionRepo = mock(PosicionRepository.class);
        lifecycle = mock(StrategyLifecycleUseCase.class);
        service = new ApiQueryService(instanciaRepo, posicionRepo, mock(LedgerRepository.class),
                mock(WalletRepository.class), lifecycle);
    }

    private InstanciaEstrategia activa(long id) {
        InstanciaEstrategia i = new InstanciaEstrategia();
        i.setId(id);
        i.setEstado(EstadoEstrategia.ACTIVA);
        return i;
    }

    private Posicion cerrada(String pnl, String comisiones, long segundo) {
        Posicion p = new Posicion();
        p.setPnl(new BigDecimal(pnl));
        p.setComisiones(new BigDecimal(comisiones));
        p.setFechaCierre(Instant.ofEpochSecond(segundo));
        return p;
    }

    @Test
    @DisplayName("El estado es DOWN si una estrategia ACTIVA no tiene proceso vivo")
    void status_is_down_when_an_active_strategy_has_no_process() {
        when(instanciaRepo.findByEstado(EstadoEstrategia.ACTIVA)).thenReturn(List.of(activa(1), activa(2)));
        when(lifecycle.idsConProceso()).thenReturn(Set.of(1L));

        Status status = service.status();

        assertEquals("DOWN", status.status());
        assertEquals(List.of(2L), status.sinProceso());
    }

    @Test
    @DisplayName("Sin estrategias activas o con todas vivas, el estado es UP")
    void status_is_up_when_all_active_strategies_are_alive() {
        when(instanciaRepo.findByEstado(EstadoEstrategia.ACTIVA)).thenReturn(List.of(activa(1)));
        when(lifecycle.idsConProceso()).thenReturn(Set.of(1L));
        assertEquals("UP", service.status().status());

        when(instanciaRepo.findByEstado(EstadoEstrategia.ACTIVA)).thenReturn(List.of());
        assertEquals("UP", service.status().status());
    }

    @Test
    @DisplayName("El PnL acumula neto, cuenta ganadas y perdidas y suma comisiones; las operaciones sin PnL cuentan 0")
    void pnl_accumulates_closed_trades() {
        when(posicionRepo.findByInstanciaIdAndAbiertaFalseOrderByFechaCierreAsc(1L)).thenReturn(List.of(
                cerrada("10.5", "0.5", 1), cerrada("-4", "0.4", 2), cerrada("0", "0.1", 3)));

        Pnl pnl = service.pnl(1L);

        assertEquals(3, pnl.operaciones());
        assertEquals(1, pnl.ganadas());
        assertEquals(1, pnl.perdidas());
        assertEquals(0, pnl.pnlTotal().compareTo(new BigDecimal("6.5")));
        assertEquals(0, pnl.comisiones().compareTo(new BigDecimal("1.0")));
        assertEquals(0, pnl.serie().get(1).acumulado().compareTo(new BigDecimal("6.5")));
    }

    @Test
    @DisplayName("El límite de resultados se acota a 1..500")
    void clamps_the_page_size() {
        assertEquals(1, ApiQueryService.page(0).getPageSize());
        assertEquals(1, ApiQueryService.page(-5).getPageSize());
        assertEquals(500, ApiQueryService.page(100_000).getPageSize());
        assertEquals(100, ApiQueryService.page(100).getPageSize());
        when(posicionRepo.findByInstanciaIdAndAbiertaOrderByIdDesc(eq(1L), eq(true), any())).thenReturn(List.of());
        assertTrue(service.positions(1L, true, 10).isEmpty());
    }
}
