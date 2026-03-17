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

@SpringBootApplication
@EntityScan(basePackages = "com.bottrading.beans")
@EnableJpaRepositories(basePackages = "com.bottrading.repositories")
public class BotApplication {
    public static void main(String[] args) {
        try {
            // Inicialización de efectos visuales (Opcional)
            ConsoleLoader.getInstance().startDots("Iniciando sistema");

            // 1. Carga y Validación estricta
            Dotenv dotenv = EnvironmentValidator.loadAndValidateEnvironment();
            
            // 1b. Validar que Python está disponible con todas las dependencias
            PythonEnvironmentValidator.validatePythonEnvironment();
            
            limpiarDirectorioLogs();
            configurarPropiedadesSistema(dotenv);

            // 2. Configuración de Spring (Banner y Logs internos reducidos)
            System.setProperty("spring.main.banner-mode", "off");
            System.setProperty("logging.level.root", "INFO");

            SpringApplication app = new SpringApplication(BotApplication.class);
            //FIXME: El banner se desactiva con la propiedad, no es necesario el método. Dejar solo una forma de hacerlo.
            //app.setBannerMode(Banner.Mode.OFF);
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

    private static void imprimirErrorCritico(String mensaje) {
        System.err.println("\n" + "!".repeat(80));
        System.err.println(" FALLO EN EL ARRANQUE DEL SISTEMA");
        System.err.println(" " + mensaje);
        System.err.println("!".repeat(80) + "\n");
    }

    private static void configurarPropiedadesSistema(Dotenv dotenv) {
        System.setProperty("spring.datasource.url", dotenv.get("DB_URL"));
        System.setProperty("spring.datasource.username", dotenv.get("DB_USER"));
        System.setProperty("spring.datasource.password", dotenv.get("DB_PASSWORD"));
    }

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