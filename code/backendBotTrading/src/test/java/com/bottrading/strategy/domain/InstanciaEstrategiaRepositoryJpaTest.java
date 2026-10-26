package com.bottrading.strategy.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

/** Consulta real contra H2 + Flyway: los mocks no ejecutan el JPQL. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("schema")
class InstanciaEstrategiaRepositoryJpaTest {

    @Autowired
    private InstanciaEstrategiaRepository repo;

    private void guardar(EstadoEstrategia estado, String capital, long wallet) {
        InstanciaEstrategia i = InstanciaEstrategia.inicializar("S", null, "1h", List.of("BTCUSDT"), false, wallet,
                new BigDecimal("0.02"), new BigDecimal(capital));
        i.setEstado(estado);
        i.setWalletAsociada(wallet);
        repo.save(i);
    }

    @Test
    @DisplayName("El capital en uso de una wallet incluye las DETENIDA, que mantienen fondos reservados (#119)")
    void paused_strategies_still_hold_capital() {
        guardar(EstadoEstrategia.ACTIVA, "100", 901L);
        guardar(EstadoEstrategia.DETENIDA, "50", 901L);
        guardar(EstadoEstrategia.TERMINADA, "999", 901L);
        guardar(EstadoEstrategia.ACTIVA, "7", 902L);

        BigDecimal enUso = repo.sumCapitalActivoByWallet(901L);

        assertEquals(0, new BigDecimal("150").compareTo(enUso));
    }

    @Test
    @DisplayName("El capital repartido (reservado + comprometido) excluye las terminadas")
    void distributed_capital_excludes_terminated() {
        guardar(EstadoEstrategia.ACTIVA, "100", 903L);
        guardar(EstadoEstrategia.TERMINADA, "40", 903L);

        BigDecimal repartido = repo.sumCapitalRepartidoByWallet(903L);

        assertEquals(0, new BigDecimal("100").compareTo(repartido));
    }

    @Test
    @DisplayName("La consulta de reanudación devuelve solo las del estado pedido y marcadas; sin marcar por defecto")
    void finds_only_marked_strategies_in_the_requested_state() {
        guardar(EstadoEstrategia.DETENIDA, "10", 904L);
        InstanciaEstrategia marcada = repo.save(marcadaEn(EstadoEstrategia.DETENIDA, 904L));
        repo.save(marcadaEn(EstadoEstrategia.TERMINADA, 904L));

        List<InstanciaEstrategia> reanudables = repo.findByEstadoAndReanudarAlArrancarTrue(EstadoEstrategia.DETENIDA);

        assertEquals(List.of(marcada.getId()), reanudables.stream().map(InstanciaEstrategia::getId).toList());
    }

    private InstanciaEstrategia marcadaEn(EstadoEstrategia estado, long wallet) {
        InstanciaEstrategia i = InstanciaEstrategia.inicializar("S", null, "1h", List.of("BTCUSDT"), false, wallet,
                new BigDecimal("0.02"), new BigDecimal("10"));
        i.setEstado(estado);
        i.setReanudarAlArrancar(true);
        return i;
    }
}
