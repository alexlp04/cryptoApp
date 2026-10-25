package com.bottrading.shared.utils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.bottrading.shared.exceptions.EnvironmentConfigException;

class PythonEnvironmentValidatorTest {

    @TempDir
    Path tmp;

    /** Un "python" falso: un script sh con el cuerpo dado. */
    private String fakePython(String body) throws IOException {
        Assumptions.assumeTrue(!System.getProperty("os.name").toLowerCase().contains("win"));
        Path script = tmp.resolve("python-fake");
        Files.writeString(script, "#!/bin/sh\n" + body + "\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        return script.toString();
    }

    @Test
    @DisplayName("Entorno correcto: versión 3.12 y ningún módulo ausente")
    void valid_environment_passes() throws IOException {
        String py = fakePython("echo '3 12'; echo ''");

        assertDoesNotThrow(() -> PythonEnvironmentValidator.validate(py, 10));
    }

    @Test
    @DisplayName("Python anterior a 3.11 se rechaza")
    void old_python_is_rejected() throws IOException {
        String py = fakePython("echo '3 9'; echo ''");

        EnvironmentConfigException e = assertThrows(EnvironmentConfigException.class,
                () -> PythonEnvironmentValidator.validate(py, 10));
        assertTrue(e.getMessage().contains("3.11"));
    }

    @Test
    @DisplayName("Los módulos ausentes se listan todos en el mensaje")
    void missing_modules_are_listed() throws IOException {
        String py = fakePython("echo '3 12'; echo 'xgboost,lightgbm'; echo ''");

        EnvironmentConfigException e = assertThrows(EnvironmentConfigException.class,
                () -> PythonEnvironmentValidator.validate(py, 10));
        assertTrue(e.getMessage().contains("xgboost, lightgbm"));
    }

    @Test
    @DisplayName("Sin tensorflow (módulo opcional) el entorno sigue siendo válido: solo faltan los modelos Keras")
    void missing_optional_module_does_not_fail() throws IOException {
        String py = fakePython("echo '3 11'; echo ''; echo 'tensorflow'");

        assertDoesNotThrow(() -> PythonEnvironmentValidator.validate(py, 10));
    }

    @Test
    @DisplayName("El script real imprime versión, ausentes obligatorios y ausentes opcionales, en ese orden")
    void check_script_prints_version_then_required_then_optional() throws Exception {
        Process p = new ProcessBuilder("python3", "-c", PythonEnvironmentValidator.CHECK_SCRIPT,
                "json,modulo_obligatorio_falso", "sys,tensorflow_falso").start();
        String[] lines = new String(p.getInputStream().readAllBytes()).split("\\R", -1);

        assertTrue(lines[0].matches("3 \\d+"));
        assertTrue(lines[1].equals("modulo_obligatorio_falso"));
        assertTrue(lines[2].equals("tensorflow_falso"));
    }

    @Test
    @DisplayName("El timeout se aplica aunque el proceso se cuelgue y no cierre su salida (#108)")
    void hanging_process_times_out() throws IOException {
        String py = fakePython("sleep 30");

        long start = System.currentTimeMillis();
        EnvironmentConfigException e = assertThrows(EnvironmentConfigException.class,
                () -> PythonEnvironmentValidator.validate(py, 1));

        assertTrue(e.getMessage().contains("timeout"));
        assertTrue(System.currentTimeMillis() - start < 10_000);
    }

    @Test
    @DisplayName("Un fallo del intérprete muestra su stderr real, no 'módulo no encontrado'")
    void interpreter_failure_reports_stderr() throws IOException {
        String py = fakePython("echo 'ImportError: libcudart.so' >&2; exit 3");

        EnvironmentConfigException e = assertThrows(EnvironmentConfigException.class,
                () -> PythonEnvironmentValidator.validate(py, 10));
        assertTrue(e.getMessage().contains("libcudart"));
    }

    @Test
    @DisplayName("Un ejecutable inexistente da un error claro")
    void missing_executable_is_reported() {
        assertThrows(EnvironmentConfigException.class,
                () -> PythonEnvironmentValidator.validate(tmp.resolve("no-existe").toString(), 5));
    }
}
