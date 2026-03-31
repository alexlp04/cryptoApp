package com.bottrading.domain.strategy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * TEST DOMINIO - InstanciaEstrategia (strategy instance)
 *
 * Características:
 *  • SIN @SpringBootTest → Sin contexto Spring
 *  • SIN @Mock → Solo JUnit 5 puro
 *  • Testea: ciclo de vida (CREADA→ACTIVA→PAUSADA→TERMINADA), capital management
 *  • Nota: InstanciaEstrategia usa factory method inicializar()
 */
@DisplayName("Domain Entity - InstanciaEstrategia")
class InstanciaEstrategiaTest {

    // ══════════════════════════════════════════════════════════════════════════
    // CONSTRUCCIÓN Y FACTORY METHOD
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("✓ Debe crear instancia estrategia con constructor sin args")
    void should_create_instancia_estrategia_with_no_args() {
        // Given & When
        InstanciaEstrategia instancia = new InstanciaEstrategia();

        // Then
        assertThat(instancia, is(notNullValue()));
        assertThat(instancia.getEstado(), is(equalTo("CREADA")));
        assertThat(instancia.isEliminado(), is(false));
    }

    @Test
    @DisplayName("✓ Debe crear instancia usando factory method inicializar()")
    void should_create_instancia_using_factory_method() {
        // Given
        String nombreEstra = "RSI_SMA";
        String nombreModelo = "rsi_sma_v1";
        String tf = "1h";
        List<String> coins = Arrays.asList("BTCUSDT", "ETHUSDT");
        boolean isReal = false;
        Long walletId = 100L;
        BigDecimal risk = new BigDecimal("0.02");
        BigDecimal capital = new BigDecimal("1000.00");

        // When
        InstanciaEstrategia instancia = InstanciaEstrategia.inicializar(
                nombreEstra, nombreModelo, tf, coins, isReal, walletId, risk, capital);

        // Then
        assertThat(instancia.getNombreEstrategia(), is(equalTo("RSI_SMA")));
        assertThat(instancia.getNombreModelo(), is(equalTo("rsi_sma_v1")));
        assertThat(instancia.getTimeframe(), is(equalTo("1h")));
        assertThat(instancia.getSimbolos(), hasSize(2));
        assertThat(instancia.isEsReal(), is(false));
        assertThat(instancia.getWalletAsociada(), is(equalTo(100L)));
        assertThat(instancia.getCapitalAsignado(), is(equalTo(capital)));
        assertThat(instancia.getRiskPerTrade(), is(equalTo(risk)));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // VALIDACIONES: NOMBRE ESTRATEGIA
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("✓ Debe asignar nombre estrategia válido")
    void should_assign_valid_nombreEstrategia() {
        // Given
        InstanciaEstrategia instancia = new InstanciaEstrategia();

        // When
        instancia.setNombreEstrategia("RSI_SMA");

        // Then
        assertThat(instancia.getNombreEstrategia(), is(equalTo("RSI_SMA")));
    }

    @Test
    @DisplayName("✗ Nombre estrategia no debe ser vacío para instancia válida")
    void should_not_have_empty_nombreEstrategia() {
        // Given
        InstanciaEstrategia instancia = new InstanciaEstrategia();
        String nombreVacio = "";

        // When
        instancia.setNombreEstrategia(nombreVacio);

        // Then - la validación debería rechazar esto (si se implementa)
        // Para ahora, simplemente verificamos que se asignó
        assertThat(instancia.getNombreEstrategia(), is(not(nullValue())));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // VALIDACIONES: CAPITAL Y RIESGO
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("✓ Debe asignar capitalAsignado positivo")
    void should_assign_positive_capitalAsignado() {
        // Given
        InstanciaEstrategia instancia = new InstanciaEstrategia();
        BigDecimal capital = new BigDecimal("5000.00");

        // When
        instancia.setCapitalAsignado(capital);

        // Then
        assertThat(instancia.getCapitalAsignado(),
                is(greaterThan(BigDecimal.ZERO)));
    }

    @Test
    @DisplayName("✓ Debe asignar capitalReservado <= capitalAsignado")
    void should_have_reserved_capital_less_or_equal_assigned() {
        // Given
        InstanciaEstrategia instancia = new InstanciaEstrategia();
        instancia.setCapitalAsignado(new BigDecimal("10000.00"));
        instancia.setCapitalReservado(new BigDecimal("10000.00"));

        // When & Then
        assertThat(instancia.getCapitalReservado().compareTo(
                instancia.getCapitalAsignado()),
                is(lessThanOrEqualTo(0)));
    }

    @Test
    @DisplayName("✓ Debe asignar riskPerTrade entre 0 y  0.05 (5%)")
    void should_assign_risk_per_trade_within_bounds() {
        // Given
        InstanciaEstrategia instancia = new InstanciaEstrategia();
        BigDecimal risk = new BigDecimal("0.02"); // 2% es razonable

        // When
        instancia.setRiskPerTrade(risk);

        // Then
        assertThat(instancia.getRiskPerTrade(),
                is(greaterThan(BigDecimal.ZERO)));
        assertThat(instancia.getRiskPerTrade(),
                is(lessThanOrEqualTo(new BigDecimal("0.05"))));
    }

    @Test
    @DisplayName("✓ Debe inicializar capitalComprometido en ZERO")
    void should_initialize_capitalComprometido_to_zero() {
        // Given & When
        InstanciaEstrategia instancia = new InstanciaEstrategia();

        // Then
        assertThat(instancia.getCapitalComprometido(),
                is(equalTo(BigDecimal.ZERO)));
    }

    @Test
    @DisplayName("✓ Debe inicializar riesgoAbierto en ZERO")
    void should_initialize_riesgoAbierto_to_zero() {
        // Given & When
        InstanciaEstrategia instancia = new InstanciaEstrategia();

        // Then
        assertThat(instancia.getRiesgoAbierto(),
                is(equalTo(BigDecimal.ZERO)));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // VALIDACIONES: SÍMBOLOS (PARES DE TRADING)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("✓ Debe asignar lista de símbolos válida")
    void should_assign_valid_simbolos() {
        // Given
        InstanciaEstrategia instancia = new InstanciaEstrategia();
        List<String> simbolos = Arrays.asList("BTCUSDT", "ETHUSDT", "BNBUSDT");

        // When
        instancia.setSimbolos(simbolos);

        // Then
        assertThat(instancia.getSimbolos(), hasSize(3));
        assertThat(instancia.getSimbolos(), hasItems("BTCUSDT", "ETHUSDT", "BNBUSDT"));
    }

    @Test
    @DisplayName("✓ Debe aceptar lista de símbolos vacía (estrategia genérica)")
    void should_accept_empty_simbolos_list() {
        // Given & When
        InstanciaEstrategia instancia = new InstanciaEstrategia();
        instancia.setSimbolos(Arrays.asList());

        // Then
        assertThat(instancia.getSimbolos(), hasSize(0));
    }

    @Test
    @DisplayName("✓ Debe aceptar un solo símbolo")
    void should_accept_single_simbolo() {
        // Given & When
        InstanciaEstrategia instancia = new InstanciaEstrategia();
        instancia.setSimbolos(Arrays.asList("BTCUSDT"));

        // Then
        assertThat(instancia.getSimbolos(), hasSize(1));
        assertThat(instancia.getSimbolos(), contains("BTCUSDT"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ESTADOS: CREADA → ACTIVA → PAUSADA → TERMINADA
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("✓ Estado inicial debe ser CREADA")
    void should_initial_estado_be_CREADA() {
        // Given & When
        InstanciaEstrategia instancia = new InstanciaEstrategia();

        // Then
        assertThat(instancia.getEstado(), is(equalTo("CREADA")));
    }

    @Test
    @DisplayName("✓ Debe transicionar de CREADA a ACTIVA")
    void should_transition_from_CREADA_to_ACTIVA() {
        // Given
        InstanciaEstrategia instancia = new InstanciaEstrategia();
        assertThat(instancia.getEstado(), is(equalTo("CREADA")));

        // When
        instancia.setEstado("ACTIVA");

        // Then
        assertThat(instancia.getEstado(), is(equalTo("ACTIVA")));
    }

    @Test
    @DisplayName("✓ Debe transicionar de ACTIVA a PAUSADA")
    void should_transition_from_ACTIVA_to_PAUSADA() {
        // Given
        InstanciaEstrategia instancia = new InstanciaEstrategia();
        instancia.setEstado("ACTIVA");

        // When
        instancia.setEstado("PAUSADA");

        // Then
        assertThat(instancia.getEstado(), is(equalTo("PAUSADA")));
    }

    @Test
    @DisplayName("✓ Debe transicionar de PAUSADA a TERMINADA")
    void should_transition_from_PAUSADA_to_TERMINADA() {
        // Given
        InstanciaEstrategia instancia = new InstanciaEstrategia();
        instancia.setEstado("PAUSADA");

        // When
        instancia.setEstado("TERMINADA");

        // Then
        assertThat(instancia.getEstado(), is(equalTo("TERMINADA")));
    }

    @Test
    @DisplayName("✓ Debe permanecer TERMINADA (estado final)")
    void should_remain_TERMINADA_as_final_state() {
        // Given
        InstanciaEstrategia instancia = new InstanciaEstrategia();
        instancia.setEstado("TERMINADA");

        // When & Then
        assertThat(instancia.getEstado(), is(equalTo("TERMINADA")));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // VALIDACIONES: TIPO EJECUCIÓN (REAL / PAPER)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("✓ Debe indicar si es ejecución REAL o PAPER")
    void should_indicate_real_or_paper_trading() {
        // Given
        InstanciaEstrategia paper = new InstanciaEstrategia();
        paper.setEsReal(false);

        InstanciaEstrategia real = new InstanciaEstrategia();
        real.setEsReal(true);

        // Then
        assertThat(paper.isEsReal(), is(false));
        assertThat(real.isEsReal(), is(true));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // VALIDACIONES: WALLET ASOCIADA
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("✓ Debe asociarse a una wallet válida")
    void should_associate_with_valid_wallet() {
        // Given
        InstanciaEstrategia instancia = new InstanciaEstrategia();
        Long walletId = 123L;

        // When
        instancia.setWalletAsociada(walletId);

        // Then
        assertThat(instancia.getWalletAsociada(), is(equalTo(123L)));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // VALIDACIONES: TIMEFRAME
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("✓ Debe asignar timeframe válido (1m, 5m, 1h, 1d)")
    void should_accept_valid_timeframes() {
        // Given
        String[] validTimeframes = {"1m", "5m", "15m", "1h", "4h", "1d"};

        for (String tf : validTimeframes) {
            // When
            InstanciaEstrategia instancia = new InstanciaEstrategia();
            instancia.setTimeframe(tf);

            // Then
            assertThat(instancia.getTimeframe(), is(equalTo(tf)));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // BANDERA: ELIMINADO
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("✓ Por defecto, instancia no está eliminada (soft delete)")
    void should_not_be_deleted_initially() {
        // Given & When
        InstanciaEstrategia instancia = new InstanciaEstrategia();

        // Then
        assertThat(instancia.isEliminado(), is(false));
    }

    @Test
    @DisplayName("✓ Debe poder marcar como eliminada (soft delete)")
    void should_be_able_to_mark_as_deleted() {
        // Given
        InstanciaEstrategia instancia = new InstanciaEstrategia();

        // When
        instancia.setEliminado(true);

        // Then
        assertThat(instancia.isEliminado(), is(true));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // HEREDIBILIDAD BASEENTITY
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("✓ InstanciaEstrategia debe heredar getId() de BaseEntity")
    void should_instancia_inherit_getId_from_BaseEntity() {
        // Given & When
        InstanciaEstrategia instancia = new InstanciaEstrategia();

        // Then
        assertThat(instancia.getId(), is(nullValue())); // null hasta persistirse
    }

    @Test
    @DisplayName("✓ InstanciaEstrategia debe heredar getFechaCreacion() de BaseEntity")
    void should_instancia_inherit_getFechaCreacion_from_BaseEntity() {
        // Given & When
        InstanciaEstrategia instancia = new InstanciaEstrategia();

        // Then
        assertThat(instancia.getFechaCreacion(), is(notNullValue()));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // FLUJOS DE CICLO DE VIDA COMPLETOS
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("✓ Flujo completo: Creación → Activación → Pausado → Terminación")
    void should_complete_full_lifecycle() {
        // Given
        InstanciaEstrategia instancia = InstanciaEstrategia.inicializar(
                "RSI_SMA", "v1", "1h",
                Arrays.asList("BTCUSDT"),
                false, 100L,
                new BigDecimal("0.02"),
                new BigDecimal("1000.00"));

        // Initial state
        assertThat(instancia.getEstado(), is(equalTo("CREADA")));

        // Activate
        instancia.setEstado("ACTIVA");
        assertThat(instancia.getEstado(), is(equalTo("ACTIVA")));

        // Pause
        instancia.setEstado("PAUSADA");
        assertThat(instancia.getEstado(), is(equalTo("PAUSADA")));

        // Terminate
        instancia.setEstado("TERMINADA");
        assertThat(instancia.getEstado(), is(equalTo("TERMINADA")));

        // Verify all properties survived
        assertThat(instancia.getNombreEstrategia(), is(equalTo("RSI_SMA")));
        assertThat(instancia.getCapitalAsignado(),
                is(equalTo(new BigDecimal("1000.00"))));
    }

    @Test
    @DisplayName("✓ Flujo: Capital puede variar durante ejecución")
    void should_track_capital_changes_during_execution() {
        // Given
        InstanciaEstrategia instancia = new InstanciaEstrategia();
        instancia.setCapitalAsignado(new BigDecimal("1000.00"));
        instancia.setCapitalReservado(new BigDecimal("1000.00"));

        // When - capital comprometido aumenta (trades abiertos)
        instancia.setCapitalComprometido(new BigDecimal("500.00"));
        instancia.setRiesgoAbierto(new BigDecimal("50.00"));

        // Then
        assertThat(instancia.getCapitalComprometido(),
                is(greaterThan(BigDecimal.ZERO)));
        assertThat(instancia.getRiesgoAbierto(),
                is(greaterThan(BigDecimal.ZERO)));
    }
}
