package com.bottrading.trading.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import com.bottrading.strategy.domain.InstanciaEstrategia;
import com.bottrading.strategy.domain.InstanciaEstrategiaRepository;

/** Las consultas derivadas de la API: si un nombre de método no resuelve, la aplicación no arranca. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("schema")
class PosicionRepositoryJpaTest {

    @Autowired
    private PosicionRepository posiciones;
    @Autowired
    private InstanciaEstrategiaRepository instancias;
    @Autowired
    private LedgerRepository ledger;

    private InstanciaEstrategia instancia() {
        return instancias.save(InstanciaEstrategia.inicializar("S", null, "1h", List.of("BTCUSDT"), false, 1L,
                new BigDecimal("0.02"), new BigDecimal("100")));
    }

    private Posicion posicion(InstanciaEstrategia i, boolean abierta, long cierre) {
        Posicion p = new Posicion();
        p.setInstancia(i);
        p.setSimbolo("BTCUSDT");
        p.setPrecioEntrada(BigDecimal.TEN);
        p.setMargenInvertido(BigDecimal.TEN);
        p.setAbierta(abierta);
        p.setFechaCierre(abierta ? null : Instant.ofEpochSecond(cierre));
        return posiciones.save(p);
    }

    @Test
    @DisplayName("Abiertas y cerradas se separan por estrategia, de la más reciente a la más antigua, con límite")
    void splits_open_and_closed_per_strategy() {
        InstanciaEstrategia a = instancia();
        InstanciaEstrategia b = instancia();
        posicion(a, true, 0);
        Posicion primera = posicion(a, false, 10);
        Posicion segunda = posicion(a, false, 20);
        posicion(b, false, 5);

        List<Posicion> cerradas = posiciones.findByInstanciaIdAndAbiertaOrderByIdDesc(a.getId(), false,
                PageRequest.of(0, 10));

        assertEquals(List.of(segunda.getId(), primera.getId()), cerradas.stream().map(Posicion::getId).toList());
        assertEquals(1, posiciones.findByInstanciaIdAndAbiertaOrderByIdDesc(a.getId(), false, PageRequest.of(0, 1)).size());
        assertEquals(1, posiciones.countByInstanciaIdAndAbiertaTrue(a.getId()));
    }

    @Test
    @DisplayName("Las cerradas salen en orden cronológico de cierre, para la curva de resultados")
    void closed_trades_come_in_closing_order() {
        InstanciaEstrategia a = instancia();
        Posicion tarde = posicion(a, false, 30);
        Posicion pronto = posicion(a, false, 10);

        List<Posicion> serie = posiciones.findByInstanciaIdAndAbiertaFalseOrderByFechaCierreAsc(a.getId());

        assertEquals(List.of(pronto.getId(), tarde.getId()), serie.stream().map(Posicion::getId).toList());
    }

    @Test
    @DisplayName("El ledger de una estrategia admite límite")
    void ledger_by_strategy_accepts_a_page() {
        for (int i = 0; i < 3; i++) {
            LedgerEntry e = new LedgerEntry();
            e.setEstrategiaId(77L);
            e.setWalletId(1L);
            e.setType(LedgerType.FEE);
            e.setAmount(BigDecimal.ONE);
            e.setBalanceAfter(BigDecimal.ONE);
            e.setTimestamp(Instant.now());
            ledger.save(e);
        }

        assertEquals(2, ledger.findByEstrategiaIdOrderByIdDesc(77L, PageRequest.of(0, 2)).size());
    }
}
