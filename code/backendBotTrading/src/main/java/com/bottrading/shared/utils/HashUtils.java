package com.bottrading.shared.utils;

import java.nio.charset.StandardCharsets;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public class HashUtils {

    private static final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    /** BCrypt limita la contraseña a 72 BYTES UTF-8 (no caracteres): 30 emojis ya la superan. */
    private static final int MAX_PASSWORD_BYTES = 72;

    private HashUtils() {
        throw new UnsupportedOperationException("Clase de utilidad, no instanciar.");
    }

    public static String hashPassword(String password) {
        if (!esPasswordValida(password)) {
            throw new IllegalArgumentException(
                    "La contraseña debe tener entre 1 y " + MAX_PASSWORD_BYTES + " bytes (UTF-8).");
        }
        return encoder.encode(password);
    }

    public static boolean verificarPassword(String rawPassword, String hashedPassword) {
        // Mitiga CVE-2025-22228 y evita que una contraseña fuera de rango reviente en login
        if (!esPasswordValida(rawPassword) || hashedPassword == null) {
            return false;
        }
        return encoder.matches(rawPassword, hashedPassword);
    }

    private static boolean esPasswordValida(String password) {
        return password != null && !password.isEmpty()
                && password.getBytes(StandardCharsets.UTF_8).length <= MAX_PASSWORD_BYTES;
    }

}
