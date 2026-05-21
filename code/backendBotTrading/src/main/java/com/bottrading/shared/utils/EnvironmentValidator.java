package com.bottrading.shared.utils;

import java.io.File;

import com.bottrading.shared.exceptions.EnvironmentConfigException;

import io.github.cdimascio.dotenv.Dotenv;

public class EnvironmentValidator {

    public static Dotenv loadAndValidateEnvironment() {
        Dotenv dotenv;
        try {
            dotenv = Dotenv.configure()
                    .directory("./")
                    .ignoreIfMissing()
                    .load();

            if (dotenv.get("DB_URL") == null) {
                File envFile = new File(".env");
                String pathAbsoluto = envFile.getAbsolutePath();
                throw new EnvironmentConfigException(
                        "No se pudo leer el archivo .env o está vacío.\n" +
                                "Ruta buscada: " + pathAbsoluto);
            }

            checkVariable(dotenv, "DB_URL");
            checkVariable(dotenv, "DB_USER");
            checkVariable(dotenv, "DB_PASSWORD");

            return dotenv;
        } catch (EnvironmentConfigException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new EnvironmentConfigException("Error crítico al procesar el entorno: " + e.getMessage(), e);
        }
    }

    private static void checkVariable(Dotenv dotenv, String key) {
        String value = dotenv.get(key);
        if (value == null || value.trim().isEmpty()) {
            throw new EnvironmentConfigException("Falta la variable requerida '" + key + "' en el archivo .env");
        }
    }
}