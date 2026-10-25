package com.bottrading.shared.utils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HashUtilsTest {

    @Test
    @DisplayName("Una contraseña normal se hashea y verifica")
    void normal_password_round_trips() {
        String hash = HashUtils.hashPassword("s3creta!");

        assertTrue(HashUtils.verificarPassword("s3creta!", hash));
        assertFalse(HashUtils.verificarPassword("otra", hash));
    }

    @Test
    @DisplayName("El límite de 72 se mide en bytes UTF-8, no en caracteres (#107)")
    void limit_is_measured_in_utf8_bytes() {
        String treintaEmojis = "😀".repeat(30); // 30 caracteres... pero 120 bytes

        assertThrows(IllegalArgumentException.class, () -> HashUtils.hashPassword(treintaEmojis));
        // en login no debe lanzar: simplemente no coincide
        assertFalse(HashUtils.verificarPassword(treintaEmojis, HashUtils.hashPassword("x")));
    }

    @Test
    @DisplayName("72 bytes exactos se aceptan y 73 se rechazan")
    void boundary_is_72_bytes() {
        assertTrue(HashUtils.verificarPassword("a".repeat(72), HashUtils.hashPassword("a".repeat(72))));
        assertThrows(IllegalArgumentException.class, () -> HashUtils.hashPassword("a".repeat(73)));
    }

    @Test
    @DisplayName("La contraseña vacía o nula se rechaza (#107)")
    void empty_or_null_password_is_rejected() {
        assertThrows(IllegalArgumentException.class, () -> HashUtils.hashPassword(""));
        assertThrows(IllegalArgumentException.class, () -> HashUtils.hashPassword(null));
        assertFalse(HashUtils.verificarPassword("", "$2a$10$abc"));
    }

    @Test
    @DisplayName("Los espacios forman parte de la contraseña")
    void spaces_are_significant() {
        String hash = HashUtils.hashPassword(" abc ");

        assertTrue(HashUtils.verificarPassword(" abc ", hash));
        assertFalse(HashUtils.verificarPassword("abc", hash));
    }
}
