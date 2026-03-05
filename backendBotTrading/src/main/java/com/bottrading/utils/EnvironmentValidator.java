package com.bottrading.utils;

import com.bottrading.exceptions.EnvironmentConfigException;
import io.github.cdimascio.dotenv.Dotenv;
import java.io.File;

public class EnvironmentValidator {

    public static Dotenv loadAndValidateEnvironment() {
    // 1. Configuramos Dotenv para que ignore si el archivo no existe inicialmente 
    // y lo cargamos manualmente para tener control total del error.
    Dotenv dotenv;
    try {
        dotenv = Dotenv.configure()
                .directory("./backendBotTrading") // Forzamos la raíz actual
                .ignoreIfMissing() 
                .load();
        
        // 2. Verificación manual robusta
        // Si no detecta ninguna de las variables clave, asumimos que no leyó el archivo
        if (dotenv.get("DB_URL") == null) {
            File envFile = new File(".env");
            String pathAbsoluto = envFile.getAbsolutePath();
            throw new EnvironmentConfigException(
                "No se pudo leer el archivo .env o está vacío.\n" +
                "Ruta buscada: " + pathAbsoluto
            );
        }

        // 3. Validación de variables individuales
        checkVariable(dotenv, "DB_URL");
        checkVariable(dotenv, "DB_USER");
        checkVariable(dotenv, "DB_PASSWORD");

        return dotenv;
        
    } catch (Exception e) {
        if (e instanceof EnvironmentConfigException) throw e;
        throw new EnvironmentConfigException("Error crítico al procesar el entorno: " + e.getMessage());
    }
}

    private static void checkVariable(Dotenv dotenv, String key) {
        String value = dotenv.get(key);
        if (value == null || value.trim().isEmpty()) {
            throw new EnvironmentConfigException("Falta la variable requerida '" + key + "' en el archivo .env");
        }
    }
}