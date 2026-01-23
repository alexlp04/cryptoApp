package com.bottrading.Utils;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public class HashUtils {

    private static final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public static String hashPassword(String password) {
        String hashed = encoder.encode(password);
        return hashed;
    }

    public static boolean verificarPassword(String rawPassword, String hashedPassword) {
        return encoder.matches(rawPassword, hashedPassword);
    }

}