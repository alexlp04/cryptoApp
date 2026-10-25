package com.bottrading;

import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import com.bottrading.shared.exceptions.EnvironmentConfigException;
import com.bottrading.shared.utils.ConsoleLoader;
import com.bottrading.shared.utils.PythonEnvironmentValidator;


/**
 * Entry point de la aplicacion Spring Boot.
 */
@SpringBootApplication
@EntityScan(basePackages = "com.bottrading")
@EnableJpaRepositories(basePackages = "com.bottrading")
public class BotApplication {
    /**
     * Inicializa entorno, valida prerequisitos y arranca el contexto Spring.
     */
    public static void main(String[] args) {
        try {
            ConsoleLoader.getInstance().startDots("Iniciando sistema");

            PythonEnvironmentValidator.validatePythonEnvironment();

            // banner, nivel de log y credenciales (./.env) los resuelve application.properties
            SpringApplication.run(BotApplication.class, args);

        } catch (EnvironmentConfigException e) {
            imprimirErrorCritico(e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            String variable = variableSinResolver(e);
            if (variable != null) {
                imprimirErrorCritico("Falta la variable '" + variable + "'. Defínela en "
                        + new File(".env").getAbsolutePath() + " (formato KEY=valor, sin comillas) "
                        + "o como variable de entorno.");
            } else {
                imprimirErrorCritico("ERROR INESPERADO: " + e.getMessage());
                e.printStackTrace();
            }
            System.exit(1);
        }
    }

    private static final Pattern PLACEHOLDER = Pattern.compile("Could not resolve placeholder '([^']+)'");

    /** Nombre de la variable ${...} que Spring no pudo resolver, si ese es el motivo del fallo. */
    static String variableSinResolver(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            Matcher m = t.getMessage() == null ? null : PLACEHOLDER.matcher(t.getMessage());
            if (m != null && m.find()) {
                return m.group(1);
            }
        }
        return null;
    }

    /**
     * Imprime un bloque de error de arranque con formato uniforme.
     */
    private static void imprimirErrorCritico(String mensaje) {
        System.err.println("\n" + "!".repeat(80));
        System.err.println(" FALLO EN EL ARRANQUE DEL SISTEMA");
        System.err.println(" " + mensaje);
        System.err.println("!".repeat(80) + "\n");
    }
}
