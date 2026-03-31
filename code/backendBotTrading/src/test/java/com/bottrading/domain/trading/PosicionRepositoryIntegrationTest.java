package com.bottrading.domain.trading;

import com.bottrading.domain.strategy.InstanciaEstrategia;
import com.bottrading.domain.strategy.InstanciaEstrategiaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest(properties = "spring.main.allow-bean-definition-overriding=true")
@EntityScan(basePackageClasses = {Posicion.class, InstanciaEstrategia.class})
@EnableJpaRepositories(basePackageClasses = {PosicionRepository.class, InstanciaEstrategiaRepository.class})
@ActiveProfiles("test")
class PosicionRepositoryIntegrationTest {

    @Autowired
    private PosicionRepository posicionRepository;

    @Autowired
    private InstanciaEstrategiaRepository instanciaRepository;

    @Test
    @DisplayName("Debe encontrar posición abierta por instancia y símbolo")
    void should_find_open_position_by_instancia_and_symbol() {
        InstanciaEstrategia instancia = instanciaRepository.save(instancia(1L));

        Posicion p = new Posicion();
        p.setInstancia(instancia);
        p.setSimbolo("BTCUSDT");
        p.setPrecioEntrada(new BigDecimal("40000.00"));
        p.setMargenInvertido(new BigDecimal("200.00"));
        p.setAbierta(true);
        posicionRepository.save(p);

        Optional<Posicion> result = posicionRepository
            .findByInstanciaAndSimboloAndAbiertaTrue(instancia, "BTCUSDT");

        assertTrue(result.isPresent());
        assertTrue(posicionRepository.existsByInstanciaAndSimboloAndAbiertaTrue(instancia, "BTCUSDT"));
    }

    @Test
    @DisplayName("No debe encontrar posición si está cerrada")
    void should_not_find_when_position_closed() {
        InstanciaEstrategia instancia = instanciaRepository.save(instancia(1L));

        Posicion p = new Posicion();
        p.setInstancia(instancia);
        p.setSimbolo("ETHUSDT");
        p.setPrecioEntrada(new BigDecimal("2000.00"));
        p.setMargenInvertido(new BigDecimal("150.00"));
        p.setAbierta(false);
        posicionRepository.save(p);

        Optional<Posicion> result = posicionRepository
            .findByInstanciaAndSimboloAndAbiertaTrue(instancia, "ETHUSDT");

        assertFalse(result.isPresent());
        assertFalse(posicionRepository.existsByInstanciaAndSimboloAndAbiertaTrue(instancia, "ETHUSDT"));
    }

    private InstanciaEstrategia instancia(Long walletId) {
        InstanciaEstrategia e = new InstanciaEstrategia();
        e.setNombreEstrategia("RSI_SMA");
        e.setTimeframe("1h");
        e.setWalletAsociada(walletId);
        e.setCapitalAsignado(new BigDecimal("1000"));
        e.setCapitalReservado(new BigDecimal("1000"));
        e.setRiskPerTrade(new BigDecimal("0.02"));
        e.setEstado("ACTIVA");
        return e;
    }
}
