package com.bottrading.backtesting.infrastructure;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.bottrading.shared.utils.PathConfig;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BacktestArtifactsCleanerTest {

    @Spy
    private BacktestArtifactsCleaner cleaner;

    // Carpeta de test temporal bajo el directorio real de resultados
    private static final String TEST_STRATEGY = "JUNIT_BST_TEST_CLEANER";
    private Path strategyDir;

    @BeforeEach
    void setUp() throws IOException {
        strategyDir = Paths.get(PathConfig.RESULTS_DIR, TEST_STRATEGY);
        Files.createDirectories(strategyDir);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (Files.exists(strategyDir)) {
            try (var stream = Files.walk(strategyDir)) {
                stream.sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try {
                                Files.delete(p);
                            } catch (IOException e) {
                                log.warn("No se pudo borrar en tearDown: {}", p);
                            }
                        });
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CONSTRUCCIÓN
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("CONSTRUCCIÓN: Instanciación del limpiador")
    class ConstructionTests {

        @Test
        @DisplayName("✓ La instancia no debe ser nula")
        void should_create_non_null_instance() {
            assertThat(new BacktestArtifactsCleaner(), is(notNullValue()));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // limpiarResultadosPrevios()
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("limpiarResultadosPrevios() — Delegación al método interno")
    class LimpiarResultadosPreviosTests {

        @Test
        @DisplayName("✓ Debe delegar en verificarYLimpiarCarpetaEstrategia con bandera true")
        void should_delegate_to_verificarYLimpiar_with_true_flag() {
            // Given
            doNothing().when(cleaner)
                    .verificarYLimpiarCarpetaEstrategia(anyString(), anyBoolean());

            // When
            cleaner.limpiarResultadosPrevios("RSISMAStrategy");

            // Then
            verify(cleaner).verificarYLimpiarCarpetaEstrategia("RSISMAStrategy", true);
        }

        @Test
        @DisplayName("✓ Debe pasar exactamente el nombre de estrategia recibido")
        void should_pass_exact_strategy_name_to_internal_method() {
            // Given
            String strategy = "ScalpingRSIStrategy";
            doNothing().when(cleaner)
                    .verificarYLimpiarCarpetaEstrategia(anyString(), anyBoolean());

            // When
            cleaner.limpiarResultadosPrevios(strategy);

            // Then
            verify(cleaner).verificarYLimpiarCarpetaEstrategia(strategy, true);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // verificarYLimpiarCarpetaEstrategia()
    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("verificarYLimpiarCarpetaEstrategia() — Comportamiento con sistema de ficheros")
    class VerificarYLimpiarTests {

        @Test
        @DisplayName("✓ No debe lanzar excepción cuando la carpeta no existe")
        void should_not_throw_when_strategy_folder_does_not_exist() {
            // Given — estrategia sin carpeta
            String estrategiaInexistente = "ESTRATEGIA_NO_EXISTE_JTEST_99";

            // When / Then — no debe lanzar ninguna excepción
            cleaner.verificarYLimpiarCarpetaEstrategia(estrategiaInexistente, true);
        }

        @Test
        @DisplayName("✓ No debe borrar archivos cuando la bandera de limpieza es false")
        void should_not_delete_files_when_flag_is_false() throws IOException {
            // Given
            Path backtest = Files.createFile(strategyDir.resolve("results_backtest.csv"));
            Path regular = Files.createFile(strategyDir.resolve("results.csv"));

            // When
            cleaner.verificarYLimpiarCarpetaEstrategia(TEST_STRATEGY, false);

            // Then — ambos archivos deben seguir existiendo
            assertThat(Files.exists(backtest), is(true));
            assertThat(Files.exists(regular), is(true));
        }

        @Test
        @DisplayName("✓ Debe eliminar sólo los archivos que contienen 'backtest' en el nombre")
        void should_delete_only_backtest_files_when_flag_is_true() throws IOException {
            // Given
            Path backtestResult = Files.createFile(strategyDir.resolve("results_backtest.csv"));
            Path backtestTrades = Files.createFile(strategyDir.resolve("trades_backtest.csv"));
            Path regular = Files.createFile(strategyDir.resolve("results.csv"));

            // When
            cleaner.verificarYLimpiarCarpetaEstrategia(TEST_STRATEGY, true);

            // Then — archivos de backtest eliminados, el regular permanece
            assertThat(Files.exists(backtestResult), is(false));
            assertThat(Files.exists(backtestTrades), is(false));
            assertThat(Files.exists(regular), is(true));
        }

        @Test
        @DisplayName("✓ No debe fallar cuando la carpeta existe pero está vacía")
        void should_not_fail_when_folder_is_empty() {
            // Given — strategyDir existe y está vacía (setUp lo garantiza)

            // When / Then — no debe lanzar ninguna excepción
            cleaner.verificarYLimpiarCarpetaEstrategia(TEST_STRATEGY, true);
        }

        @Test
        @DisplayName("✓ Debe mantener todos los archivos cuando no hay ninguno con 'backtest'")
        void should_keep_all_files_when_none_are_named_backtest() throws IOException {
            // Given
            Path file1 = Files.createFile(strategyDir.resolve("results.csv"));
            Path file2 = Files.createFile(strategyDir.resolve("trades.csv"));
            Path file3 = Files.createFile(strategyDir.resolve("summary.txt"));

            // When
            cleaner.verificarYLimpiarCarpetaEstrategia(TEST_STRATEGY, true);

            // Then — ningún archivo debe ser eliminado
            assertThat(Files.exists(file1), is(true));
            assertThat(Files.exists(file2), is(true));
            assertThat(Files.exists(file3), is(true));
        }

        @Test
        @DisplayName("✓ Debe eliminar todos los archivos que contienen 'backtest' en nombre compuesto")
        void should_delete_all_files_with_backtest_in_name() throws IOException {
            // Given — todos los archivos son de backtest
            Path f1 = Files.createFile(strategyDir.resolve("btcusdt_results_backtest.csv"));
            Path f2 = Files.createFile(strategyDir.resolve("ethusdt_results_backtest.csv"));
            Path f3 = Files.createFile(strategyDir.resolve("bnbusdt_results_backtest.csv"));

            // When
            cleaner.verificarYLimpiarCarpetaEstrategia(TEST_STRATEGY, true);

            // Then — todos deben ser eliminados
            assertThat(Files.exists(f1), is(false));
            assertThat(Files.exists(f2), is(false));
            assertThat(Files.exists(f3), is(false));
        }

        @Test
        @DisplayName("✓ Debe implementar BacktestPersistencePort")
        void should_implement_backtest_persistence_port() {
            assertThat(cleaner instanceof
                    com.bottrading.backtesting.application.port.out.BacktestPersistencePort,
                    is(true));
        }
    }
}
