package com.bottrading.domain.strategy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest(properties = "spring.main.allow-bean-definition-overriding=true")
@EntityScan(basePackageClasses = InstanciaEstrategia.class)
@EnableJpaRepositories(basePackageClasses = InstanciaEstrategiaRepository.class)
@ActiveProfiles("test")
class InstanciaEstrategiaRepositoryIntegrationTest {

    @Autowired
    private InstanciaEstrategiaRepository repository;

    @Test
    @DisplayName("sumCapitalActivoByWallet debe sumar solo estrategias ACTIVAS")
    void should_sum_only_active_capital() {
        repository.save(instancia(1L, "ACTIVA", "100"));
        repository.save(instancia(1L, "ACTIVA", "250"));
        repository.save(instancia(1L, "DETENIDA", "999"));

        BigDecimal sum = repository.sumCapitalActivoByWallet(1L);

        assertEquals(0, sum.compareTo(new BigDecimal("350.00")));
    }

    @Test
    @DisplayName("findByWalletAsociadaAndEstado debe filtrar correctamente")
    void should_filter_by_wallet_and_state() {
        repository.save(instancia(1L, "ACTIVA", "100"));
        repository.save(instancia(1L, "DETENIDA", "200"));
        repository.save(instancia(2L, "ACTIVA", "300"));

        List<InstanciaEstrategia> activeWallet1 = repository.findByWalletAsociadaAndEstado(1L, "ACTIVA");

        assertEquals(1, activeWallet1.size());
        assertEquals("ACTIVA", activeWallet1.get(0).getEstado());
        assertEquals(1L, activeWallet1.get(0).getWalletAsociada());
    }

    private InstanciaEstrategia instancia(Long walletId, String estado, String capital) {
        InstanciaEstrategia e = new InstanciaEstrategia();
        e.setNombreEstrategia("RSI_SMA");
        e.setTimeframe("1h");
        e.setWalletAsociada(walletId);
        e.setCapitalAsignado(new BigDecimal(capital));
        e.setCapitalReservado(new BigDecimal(capital));
        e.setRiskPerTrade(new BigDecimal("0.02"));
        e.setEstado(estado);
        return e;
    }
}
