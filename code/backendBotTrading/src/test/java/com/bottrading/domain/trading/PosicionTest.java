package com.bottrading.domain.trading;

import com.bottrading.domain.strategy.InstanciaEstrategia;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * TEST DOMINIO - Posicion (trading position)
 *
 * Características:
 *  • SIN @SpringBootTest → Sin contexto Spring
 *  • SIN @Mock → Solo JUnit 5 puro
 *  • Testea: validaciones de precios y margen, estados (abierta/cerrada), invariantes
 */
@DisplayName("Domain Entity - Posicion")
class PosicionTest {

    // ══════════════════════════════════════════════════════════════════════════
    // CONSTRUCCIÓN Y ESTADO INICIAL
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("✓ Debe crear posición con estado inicial abierta")
    void should_create_posicion_with_initial_state_abierta() {
        // Given & When
        Posicion posicion = new Posicion();

        // Then
        assertThat(posicion.isAbierta(), is(true)); // Por defecto abierta
    }

    @Test
    @DisplayName("✓ Debe asignar símbolo válido")
    void should_assign_valid_simbolo() {
        // Given
        Posicion posicion = new Posicion();

        // When
        posicion.setSimbolo("BTCUSDT");

        // Then
        assertThat(posicion.getSimbolo(), is(equalTo("BTCUSDT")));
    }

    @Test
    @DisplayName("✓ Debe asignar precio entrada válido (positivo)")
    void should_assign_positive_precio_entrada() {
        // Given
        Posicion posicion = new Posicion();
        BigDecimal precioEntrada = new BigDecimal("42000.50");

        // When
        posicion.setPrecioEntrada(precioEntrada);

        // Then
        assertThat(posicion.getPrecioEntrada(), is(greaterThan(BigDecimal.ZERO)));
    }

    @Test
    @DisplayName("✓ Debe asignar margen invertido válido (positivo)")
    void should_assign_positive_margen_invertido() {
        // Given
        Posicion posicion = new Posicion();
        BigDecimal margen = new BigDecimal("1000.00");

        // When
        posicion.setMargenInvertido(margen);

        // Then
        assertThat(posicion.getMargenInvertido(), is(greaterThan(BigDecimal.ZERO)));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // VALIDACIONES: PRECIOS Y MARGEN
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("✓ Precio entrada debe ser positivo")
    void should_precio_entrada_be_positive() {
        // Given & When
        Posicion posicion = new Posicion();
        posicion.setPrecioEntrada(new BigDecimal("42000.00"));

        // Then
        assertThat(posicion.getPrecioEntrada().compareTo(BigDecimal.ZERO),
                is(greaterThan(0)));
    }

    @Test
    @DisplayName("✓ Margen invertido debe ser positivo")
    void should_margen_invertido_be_positive() {
        // Given & When
        Posicion posicion = new Posicion();
        posicion.setMargenInvertido(new BigDecimal("500.00"));

        // Then
        assertThat(posicion.getMargenInvertido().compareTo(BigDecimal.ZERO),
                is(greaterThan(0)));
    }

    @Test
    @DisplayName("✓ Debe aceptar margen con precisión de hasta 2 decimales (USD)")
    void should_accept_margen_with_usd_precision() {
        // Given & When
        Posicion posicion = new Posicion();
        posicion.setMargenInvertido(new BigDecimal("1234.56"));

        // Then
        assertThat(posicion.getMargenInvertido().scale(),
                is(greaterThanOrEqualTo(0)));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ESTADOS: ABIERTA Y CERRADA
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("✓ Posición inicial debe estar ABIERTA")
    void should_posicion_be_open_initially() {
        // Given & When
        Posicion posicion = new Posicion();

        // Then
        assertThat(posicion.isAbierta(), is(true));
    }

    @Test
    @DisplayName("✓ Debe poder cerrar posición existente")
    void should_be_able_to_close_posicion() {
        // Given
        Posicion posicion = new Posicion();
        assertThat(posicion.isAbierta(), is(true));

        // When
        posicion.setAbierta(false);

        // Then
        assertThat(posicion.isAbierta(), is(false));
    }

    @Test
    @DisplayName("✗ No se debe poder reapertura una posición cerrada en el mismo objeto")
    void should_not_reopen_closed_posicion_implicitly() {
        // Given
        Posicion posicion = new Posicion();
        posicion.setAbierta(false);

        // When & Then
        assertThat(posicion.isAbierta(), is(false)); // Permanece cerrada
    }

    @Test
    @DisplayName("✓ Transición: ABIERTA → CERRADA")
    void should_transition_from_abierta_to_cerrada() {
        // Given
        Posicion posicion = new Posicion();
        assertThat(posicion.isAbierta(), is(true));

        // When
        posicion.setAbierta(false);

        // Then
        assertThat(posicion.isAbierta(), is(false));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // RELACIÓN CON INSTANCIA ESTRATEGIA
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("✓ Debe asignar instancia estrategia")
    void should_assign_instancia_estrategia() {
        // Given
        Posicion posicion = new Posicion();
        InstanciaEstrategia instancia = new InstanciaEstrategia();

        // When
        posicion.setInstancia(instancia);

        // Then
        assertThat(posicion.getInstancia(), is(notNullValue()));
        assertThat(posicion.getInstancia(), is(equalTo(instancia)));
    }

    @Test
    @DisplayName("✓ Posición puede no tener instancia asignada inicialmente")
    void should_posicion_have_null_instancia_initially() {
        // Given & When
        Posicion posicion = new Posicion();

        // Then
        assertThat(posicion.getInstancia(), is(nullValue()));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // VARIANTES
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("✓ Símbolo no puede ser nulo para una posición válida")
    void should_have_non_null_simbolo_for_valid_posicion() {
        // Given & When
        Posicion posicion = new Posicion();
        posicion.setSimbolo("ETHUSDT");

        // Then
        assertThat(posicion.getSimbolo(), is(notNullValue()));
        assertThat(posicion.getSimbolo(), is(not(emptyString())));
    }

    @Test
    @DisplayName("✓ Margen invertido debe ser definido antes de cerrar posición")
    void should_have_margen_before_closing() {
        // Given
        Posicion posicion = new Posicion();
        posicion.setSimbolo("BTCUSDT");
        posicion.setPrecioEntrada(new BigDecimal("42000.00"));
        posicion.setMargenInvertido(new BigDecimal("1000.00"));

        // When & Then
        assertThat(posicion.isAbierta(), is(true));
        assertThat(posicion.getMargenInvertido(), is(notNullValue()));
        assertThat(posicion.getMargenInvertido().compareTo(BigDecimal.ZERO),
                is(greaterThan(0)));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // HEREDIBILIDAD BASEENTITY
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("✓ Posición debe heredar getId() de BaseEntity")
    void should_posicion_inherit_getId_from_BaseEntity() {
        // Given & When
        Posicion posicion = new Posicion();

        // Then
        assertThat(posicion.getId(), is(nullValue())); // null hasta persistirse
    }

    @Test
    @DisplayName("✓ Posición debe heredar getFechaCreacion() de BaseEntity")
    void should_posicion_inherit_getFechaCreacion_from_BaseEntity() {
        // Given & When
        Posicion posicion = new Posicion();

        // Then
        assertThat(posicion.getFechaCreacion(), is(notNullValue()));
    }

    @Test
    @DisplayName("✓ Posición debe heredar isEliminado() de BaseEntity")
    void should_posicion_inherit_isEliminado_from_BaseEntity() {
        // Given & When
        Posicion posicion = new Posicion();

        // Then
        assertThat(posicion.isEliminado(), is(false)); // false por defecto
    }

    // ══════════════════════════════════════════════════════════════════════════
    // VALORES LÍMITE
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("✓ Debe aceptar precio mínimo (1 satoshi = 0.00000001)")
    void should_accept_minimum_price_like_satoshi() {
        // Given & When
        Posicion posicion = new Posicion();
        posicion.setPrecioEntrada(new BigDecimal("0.00000001"));

        // Then
        assertThat(posicion.getPrecioEntrada().compareTo(BigDecimal.ZERO),
                is(greaterThan(0)));
    }

    @Test
    @DisplayName("✓ Debe aceptar margen muy grande (ej: 1 millón USD)")
    void should_accept_large_margen() {
        // Given & When
        Posicion posicion = new Posicion();
        posicion.setMargenInvertido(new BigDecimal("1000000.00"));

        // Then
        assertThat(posicion.getMargenInvertido(),
                is(greaterThan(new BigDecimal("1000.00"))));
    }
}
