package com.bottrading.utils;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public class HashUtils {

    private static final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private static final int MAX_PASSWORD_LENGTH = 72;

    private HashUtils() {
        throw new UnsupportedOperationException("Clase de utilidad, no instanciar.");
    }

    public static String hashPassword(String password) {
        // Enforce maximum password length to mitigate CVE-2025-22228
        if (password == null || password.length() > MAX_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("Password must be between 1 and " + MAX_PASSWORD_LENGTH + " characters");
        }
        return encoder.encode(password);
    }

    public static boolean verificarPassword(String rawPassword, String hashedPassword) {
        // Enforce maximum password length to mitigate CVE-2025-22228
        if (rawPassword == null || rawPassword.length() > MAX_PASSWORD_LENGTH) {
            return false; // Reject oversized passwords
        }
        return encoder.matches(rawPassword, hashedPassword);
    }

}