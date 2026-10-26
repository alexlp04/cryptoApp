package com.bottrading.shared.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class PathConfigTest {

    @Test
    @DisplayName("Una raíz explícita manda sobre la búsqueda desde user.dir (imagen Docker sin la estructura del repo)")
    void explicit_root_wins(@TempDir Path tmp) {
        assertEquals(tmp.toAbsolutePath().toString(), PathConfig.calculateProjectRoot(tmp.toString(), "/otro/sitio"));
    }

    @Test
    @DisplayName("Sin raíz explícita sube desde user.dir hasta la carpeta que contiene code/backendBotTrading")
    void finds_root_walking_up(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("code/backendBotTrading/src"));

        String root = PathConfig.calculateProjectRoot(null, tmp.resolve("code/backendBotTrading/src").toString());

        assertEquals(tmp.toAbsolutePath().toString(), root);
    }

    @Test
    @DisplayName("Sin estructura ni raíz explícita cae a user.dir")
    void falls_back_to_user_dir(@TempDir Path tmp) {
        assertEquals(tmp.toString(), PathConfig.calculateProjectRoot("  ", tmp.toString()));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"../x", "a/b", "a\\b", "..", "/etc/passwd"})
    @DisplayName("Los nombres que escapan del directorio se rechazan (también null)")
    void traversal_names_are_rejected(String nombre) {
        assertThrows(IllegalArgumentException.class, () -> PathConfig.getValidStrategyPath(nombre));
        assertFalse(PathConfig.existeEstrategia(nombre));
        assertFalse(PathConfig.existeModelo(nombre, "1h", "BTCUSDT"));
        assertFalse(PathConfig.existeModelo("rf", nombre, "BTCUSDT"));
        assertFalse(PathConfig.existeModelo("rf", "1h", nombre));
    }
}
