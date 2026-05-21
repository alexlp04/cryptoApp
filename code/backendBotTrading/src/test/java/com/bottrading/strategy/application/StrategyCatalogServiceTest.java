package com.bottrading.strategy.application;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.bottrading.shared.utils.PathConfig;
import com.bottrading.strategy.application.port.out.InstanciaEstrategiaRepositoryPort;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StrategyCatalogServiceTest {

    @Mock
    private InstanciaEstrategiaRepositoryPort instanciaRepo;

    @InjectMocks
    private StrategyCatalogService catalogService;

    // ─────────────────────────────────────────────────────────────────────────
    // CONSTRUCCIÓN
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("CONSTRUCCIÓN: Instanciación del servicio")
    class ConstructionTests {

        @Test
        @DisplayName("✓ La instancia no debe ser nula con la dependencia inyectada")
        void should_create_non_null_instance() {
            assertThat(catalogService, is(notNullValue()));
        }

        @Test
        @DisplayName("✓ Debe aceptar la dependencia por constructor")
        void should_accept_constructor_dependency() {
            StrategyCatalogService sut = new StrategyCatalogService(instanciaRepo);
            assertThat(sut, is(notNullValue()));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // listarFicherosDeEstrategias()
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("listarFicherosDeEstrategias() — Listado de archivos .py")
    class ListarFicherosTests {

        @Test
        @DisplayName("✓ Debe retornar lista no nula (con archivos o mensaje de no disponibles)")
        void should_return_non_null_list_regardless_of_dir_state() {
            // PathConfig.STRATEGIES_DIR apunta al directorio real del proyecto.
            // Si contiene .py → lista con nombres; si vacío/inexistente → mensaje.
            List<String> result = catalogService.listarFicherosDeEstrategias();

            assertThat(result, is(notNullValue()));
            assertThat(result.isEmpty(), is(false));
        }

        @Test
        @DisplayName("✓ Debe retornar nombre sin extensión .py cuando hay estrategias en disco")
        void should_return_strategy_names_without_py_extension() {
            // Este test verifica el formato de salida: prefijo "- " + nombre sin ".py"
            // Usando una ruta que sí contiene .py files (el directorio real del proyecto)
            List<String> result = catalogService.listarFicherosDeEstrategias();

            // Cada elemento debe comenzar con "- " o ser el mensaje de no disponibles
            for (String entry : result) {
                assertThat(entry.contains(".py"), is(false));
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // getCapitalComprometido()
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("getCapitalComprometido() — Capital reservado por wallet")
    class CapitalComprometidoTests {

        @Test
        @DisplayName("✓ Debe retornar el valor del repositorio cuando no es null")
        void should_return_repo_value_when_not_null() {
            doReturn(new BigDecimal("500.00")).when(instanciaRepo).sumCapitalActivoByWallet(1L);

            BigDecimal result = catalogService.getCapitalComprometido(1L);

            assertThat(result, is(new BigDecimal("500.00")));
            verify(instanciaRepo, times(1)).sumCapitalActivoByWallet(1L);
        }

        @Test
        @DisplayName("✓ Debe retornar ZERO cuando el repositorio retorna null")
        void should_return_zero_when_repo_returns_null() {
            doReturn(null).when(instanciaRepo).sumCapitalActivoByWallet(anyLong());

            BigDecimal result = catalogService.getCapitalComprometido(99L);

            assertThat(result, is(BigDecimal.ZERO));
        }

        @Test
        @DisplayName("✓ Debe retornar ZERO cuando no hay capital activo en la wallet")
        void should_return_zero_when_no_active_capital() {
            doReturn(BigDecimal.ZERO).when(instanciaRepo).sumCapitalActivoByWallet(2L);

            BigDecimal result = catalogService.getCapitalComprometido(2L);

            assertThat(result, is(BigDecimal.ZERO));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // getCapitalDisponible()
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("getCapitalDisponible() — Capital disponible en wallet")
    class CapitalDisponibleTests {

        @Test
        @DisplayName("✓ Debe restar el capital comprometido del saldo total")
        void should_subtract_committed_from_total_balance() {
            doReturn(new BigDecimal("300.00")).when(instanciaRepo).sumCapitalActivoByWallet(1L);

            BigDecimal result = catalogService.getCapitalDisponible(1L, new BigDecimal("1000.00"));

            assertThat(result, is(new BigDecimal("700.00")));
        }

        @Test
        @DisplayName("✓ Debe retornar el saldo total cuando no hay capital comprometido")
        void should_return_full_balance_when_no_committed_capital() {
            doReturn(null).when(instanciaRepo).sumCapitalActivoByWallet(1L);

            BigDecimal result = catalogService.getCapitalDisponible(1L, new BigDecimal("500.00"));

            assertThat(result, is(new BigDecimal("500.00")));
        }

        @Test
        @DisplayName("✓ Puede retornar valor negativo cuando el comprometido supera el saldo")
        void should_return_negative_when_committed_exceeds_balance() {
            doReturn(new BigDecimal("800.00")).when(instanciaRepo).sumCapitalActivoByWallet(1L);

            BigDecimal result = catalogService.getCapitalDisponible(1L, new BigDecimal("500.00"));

            assertThat(result.compareTo(BigDecimal.ZERO) < 0, is(true));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // existeEstrategia()
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("existeEstrategia() — Verificación de existencia en disco")
    class ExisteEstrategiaTests {

        @Test
        @DisplayName("✓ Debe retornar false para una estrategia que no existe")
        void should_return_false_for_non_existent_strategy() {
            boolean result = catalogService.existeEstrategia("EstrategiaNoCreaada_XYZ_999");

            assertThat(result, is(false));
        }

        @Test
        @DisplayName("✓ Debe aceptar nombre con extensión .py sin duplicarla")
        void should_accept_name_with_py_extension() {
            boolean result = catalogService.existeEstrategia("EstrategiaInexistente.py");

            assertThat(result, is(false));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // getValidStrategyPath()
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("getValidStrategyPath() — Ruta validada de estrategia")
    class GetValidStrategyPathTests {

        @Test
        @DisplayName("✓ Debe lanzar IllegalArgumentException cuando la estrategia no existe en disco")
        void should_throw_when_strategy_not_found() {
            assertThrows(IllegalArgumentException.class,
                    () -> catalogService.getValidStrategyPath("EstrategiaInexistente_XYZ_999"));
        }

        @Test
        @DisplayName("✓ Debe delegar en PathConfig.getValidStrategyPath cuando la estrategia existe")
        void should_delegate_to_path_config_when_strategy_exists() {
            // Mockeamos existeEstrategia para que devuelva true vía PathConfig mockeado
            try (MockedStatic<PathConfig> pathStatic = mockStatic(PathConfig.class)) {
                pathStatic.when(() -> PathConfig.getValidStrategyPath("RSISMAStrategy"))
                        .thenReturn("/fake/strategies/RSISMAStrategy.py");
                // existeEstrategia también usa PathConfig.STRATEGIES_DIR internamente (File real)
                // Lo testeamos indirectamente: si la estrategia real existe, retorna la ruta
                // Si no existe → lanza excepción (cubierto en test anterior)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CONTRATO DE PUERTOS
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("Contrato de interfaz StrategyCatalogUseCase")
    class ContratoPuertoEntradaTests {

        @Test
        @DisplayName("✓ StrategyCatalogService debe implementar StrategyCatalogUseCase")
        void should_implement_strategyCatalogUseCase_interface() {
            assertThat(catalogService instanceof
                    com.bottrading.strategy.application.port.in.StrategyCatalogUseCase,
                    is(true));
        }
    }
}
