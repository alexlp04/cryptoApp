package com.bottrading.shared.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Verifica la resolución del intérprete Python según el sistema operativo.
 * El venv coloca el ejecutable en rutas distintas: Scripts/ en Windows, bin/ en POSIX.
 */
class AppConstantsTest {

    @ParameterizedTest
    @ValueSource(strings = {"Windows 11", "Windows 10", "windows server 2022"})
    @DisplayName("En Windows usa el layout Scripts/python.exe")
    void resolvesWindowsInterpreter(String osName) {
        assertEquals(
                AppConstants.PYTHON_EXECUTABLE_WINDOWS,
                AppConstants.resolveDefaultPythonExecutable(null, osName));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Linux", "Mac OS X", "FreeBSD"})
    @DisplayName("En sistemas POSIX usa el layout bin/python3")
    void resolvesPosixInterpreter(String osName) {
        assertEquals(
                AppConstants.PYTHON_EXECUTABLE_POSIX,
                AppConstants.resolveDefaultPythonExecutable(null, osName));
    }

    @Test
    @DisplayName("Un os.name desconocido cae al layout POSIX")
    void fallsBackToPosixWhenOsIsUnknown() {
        assertEquals(
                AppConstants.PYTHON_EXECUTABLE_POSIX,
                AppConstants.resolveDefaultPythonExecutable(null, null));
    }

    @Test
    @DisplayName("La variable de entorno tiene prioridad sobre la detección de SO")
    void environmentOverrideWinsOverOsDetection() {
        String custom = "/opt/python3.11/bin/python3";
        assertEquals(custom, AppConstants.resolveDefaultPythonExecutable(custom, "Windows 11"));
        assertEquals(custom, AppConstants.resolveDefaultPythonExecutable(custom, "Linux"));
    }

    @Test
    @DisplayName("Una variable de entorno vacía o en blanco se ignora")
    void blankEnvironmentOverrideIsIgnored() {
        assertEquals(
                AppConstants.PYTHON_EXECUTABLE_POSIX,
                AppConstants.resolveDefaultPythonExecutable("", "Linux"));
        assertEquals(
                AppConstants.PYTHON_EXECUTABLE_WINDOWS,
                AppConstants.resolveDefaultPythonExecutable("   ", "Windows 11"));
    }
}
