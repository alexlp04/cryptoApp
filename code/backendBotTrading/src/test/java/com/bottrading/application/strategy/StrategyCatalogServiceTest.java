package com.bottrading.application.strategy;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.bottrading.domain.strategy.InstanciaEstrategiaRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Test unitario para StrategyCatalogService.
 * Cubre: capital comprometido/disponible, validación de ruta de estrategia.
 * Los métodos dependientes del filesystem (listarFicherosDeEstrategias, existeEstrategia)
 * se limitan a tests de integración con directorios temporales.
 *
 * Total: ~9 tests en 4 @Nested clases.
 */
@Slf4j
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StrategyCatalogServiceTest {

    @Mock
    private InstanciaEstrategiaRepository instanciaRepo;

    @InjectMocks
    private StrategyCatalogService service;

    // ========== CONSTRUCCIÓN ==========
    @Nested
    @DisplayName("CONSTRUCCIÓN: Instanciación del servicio")
    class ConstructionTests {

        @Test
        @DisplayName("✓ Debe instanciar el servicio correctamente")
        void should_instantiate_service() {
            assertThat(service, is(notNullValue()));
        }
    }

    // ========== CAPITAL COMPROMETIDO ==========
    @Nested
    @DisplayName("getCapitalComprometido() — Consulta de capital en reserva")
    class CapitalComprometidoTests {

        @Test
        @DisplayName("✓ Debe retornar la suma del repositorio cuando hay capital activo")
        void should_return_repository_sum_when_exists() {
            // Given
            when(instanciaRepo.sumCapitalActivoByWallet(1L)).thenReturn(new BigDecimal("750.00"));

            // When
            BigDecimal result = service.getCapitalComprometido(1L);

            // Then
            assertThat(result, is(equalTo(new BigDecimal("750.00"))));
            verify(instanciaRepo, times(1)).sumCapitalActivoByWallet(1L);
        }

        @Test
        @DisplayName("✓ Debe retornar ZERO cuando el repositorio devuelve null")
        void should_return_zero_when_repository_returns_null() {
            // Given
            when(instanciaRepo.sumCapitalActivoByWallet(2L)).thenReturn(null);

            // When
            BigDecimal result = service.getCapitalComprometido(2L);

            // Then
            assertThat(result, is(equalTo(BigDecimal.ZERO)));
        }

        @Test
        @DisplayName("✓ Debe retornar ZERO cuando wallet sin capital activo")
        void should_return_zero_when_no_active_capital() {
            // Given
            when(instanciaRepo.sumCapitalActivoByWallet(3L)).thenReturn(BigDecimal.ZERO);

            // When
            BigDecimal result = service.getCapitalComprometido(3L);

            // Then
            assertThat(result.compareTo(BigDecimal.ZERO), is(0));
        }
    }

    // ========== CAPITAL DISPONIBLE ==========
    @Nested
    @DisplayName("getCapitalDisponible() — Cálculo de capital disponible")
    class CapitalDisponibleTests {

        @Test
        @DisplayName("✓ Debe retornar saldoTotal - capitalComprometido")
        void should_subtract_comprometido_from_total() {
            // Given
            when(instanciaRepo.sumCapitalActivoByWallet(1L)).thenReturn(new BigDecimal("300.00"));

            // When
            BigDecimal result = service.getCapitalDisponible(1L, new BigDecimal("1000.00"));

            // Then
            assertThat(result, is(equalTo(new BigDecimal("700.00"))));
        }

        @Test
        @DisplayName("✓ Debe retornar el saldo total cuando no hay capital comprometido")
        void should_return_total_when_no_capital_committed() {
            // Given
            when(instanciaRepo.sumCapitalActivoByWallet(1L)).thenReturn(null);

            // When
            BigDecimal result = service.getCapitalDisponible(1L, new BigDecimal("500.00"));

            // Then
            assertThat(result, is(equalTo(new BigDecimal("500.00"))));
        }

        @Test
        @DisplayName("✓ Debe retornar ZERO cuando el capital comprometido iguala el saldo total")
        void should_return_zero_when_fully_committed() {
            // Given
            when(instanciaRepo.sumCapitalActivoByWallet(1L)).thenReturn(new BigDecimal("1000.00"));

            // When
            BigDecimal result = service.getCapitalDisponible(1L, new BigDecimal("1000.00"));

            // Then
            assertThat(result.compareTo(BigDecimal.ZERO), is(0));
        }
    }

    // ========== VALIDAR RUTA DE ESTRATEGIA ==========
    @Nested
    @DisplayName("getValidStrategyPath() — Validación de fichero en disco")
    class GetValidStrategyPathTests {

        @Test
        @DisplayName("✓ Debe lanzar IllegalArgumentException si la estrategia no existe en disco")
        void should_throw_for_nonexistent_strategy() {
            // When & Then — estrategia inexistente garantizada
            assertThrows(IllegalArgumentException.class,
                    () -> service.getValidStrategyPath("EstrategiaInexistente_QueNoExiste_12345"));
        }

        @Test
        @DisplayName("✓ El mensaje de excepción debe incluir el nombre de la estrategia")
        void should_include_strategy_name_in_exception_message() {
            // When
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.getValidStrategyPath("FakeStrategy_NotFound"));

            // Then
            assertThat(ex.getMessage(), containsString("FakeStrategy_NotFound"));
        }
    }
}
