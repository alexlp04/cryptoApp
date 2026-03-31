package com.bottrading;
import java.io.File;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import com.bottrading.exceptions.EnvironmentConfigException;
import com.bottrading.utils.ConsoleLoader;
import com.bottrading.utils.EnvironmentValidator;
import com.bottrading.utils.PythonEnvironmentValidator;

import io.github.cdimascio.dotenv.Dotenv;

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

            Dotenv dotenv = EnvironmentValidator.loadAndValidateEnvironment();
            PythonEnvironmentValidator.validatePythonEnvironment();

            limpiarDirectorioLogs();
            configurarPropiedadesSistema(dotenv);

            System.setProperty("spring.main.banner-mode", "off");
            System.setProperty("logging.level.root", "INFO");

            SpringApplication app = new SpringApplication(BotApplication.class);

            app.setLogStartupInfo(false);
            
            app.run(args);

        } catch (EnvironmentConfigException e) {
            imprimirErrorCritico(e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            imprimirErrorCritico("ERROR INESPERADO: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
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

    /**
     * Carga en propiedades de sistema la configuracion minima de datasource.
     */
    private static void configurarPropiedadesSistema(Dotenv dotenv) {
        System.setProperty("spring.datasource.url", dotenv.get("DB_URL"));
        System.setProperty("spring.datasource.username", dotenv.get("DB_USER"));
        System.setProperty("spring.datasource.password", dotenv.get("DB_PASSWORD"));
    }

    /**
     * Elimina logs previos para iniciar una sesion limpia.
     */
    private static void limpiarDirectorioLogs() {
        File carpetaLogs = new File("logs");
        if (carpetaLogs.exists() && carpetaLogs.isDirectory()) {
            File[] archivos = carpetaLogs.listFiles((dir, name) -> name.endsWith(".log"));
            if (archivos != null) {
                for (File f : archivos) f.delete();
            }
        }
    }
}