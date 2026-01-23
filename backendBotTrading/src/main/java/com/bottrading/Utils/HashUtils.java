package com.bottrading.utils;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public class HashUtils {

    private static final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    // CVE-2025-22228 Mitigation: Limit password length to 72 chars (bcrypt limit)
    private static final int MAX_PASSWORD_LENGTH = 72;

    public static String hashPassword(String password) {
        // Enforce maximum password length to mitigate CVE-2025-22228
        if (password == null || password.length() > MAX_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("Password must be between 1 and " + MAX_PASSWORD_LENGTH + " characters");
        }
        String hashed = encoder.encode(password);
        return hashed;
    }

    public static boolean verificarPassword(String rawPassword, String hashedPassword) {
        // Enforce maximum password length to mitigate CVE-2025-22228
        if (rawPassword == null || rawPassword.length() > MAX_PASSWORD_LENGTH) {
            return false; // Reject oversized passwords
        }
        return encoder.matches(rawPassword, hashedPassword);
    }

}