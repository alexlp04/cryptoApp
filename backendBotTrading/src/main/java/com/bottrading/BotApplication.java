package com.bottrading;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import com.bottrading.utils.AppConstants;
import com.bottrading.utils.ConsoleLoader;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;

@SpringBootApplication
@EntityScan(basePackages = "com.bottrading.beans")
@EnableJpaRepositories(basePackages = "com.bottrading.repositories")
public class BotApplication {
    public static void main(String[] args) {
        // 1. CARGA DEL ENTORNO (.env)
        ConsoleLoader.getInstance().startDots();
        String executionPath = System.getProperty("user.dir");
        Dotenv dotenv;

        if (Files.exists(Paths.get(executionPath, AppConstants.DIR_BACKEND, AppConstants.EXTENSION_ENV))) {
            dotenv = Dotenv.configure().directory("./" + AppConstants.DIR_BACKEND).ignoreIfMissing().load();
        } else {
            dotenv = Dotenv.configure().directory("./").ignoreIfMissing().load();
        }
        limpiarDirectorioLogs();
        configurarPropiedadesSistema(dotenv);

        // 2. CONFIGURACIÓN DE INTERFAZ (Apagamos el Banner visual de Spring)
        System.setProperty("spring.main.banner-mode", "off");

        // 3. ARRANQUE PERSONALIZADO
        SpringApplication app = new SpringApplication(BotApplication.class);

        // Apagamos el Banner visual (reforzado)
        app.setBannerMode(Banner.Mode.OFF);
        // Apagamos el log de "Starting BotApplication..."
        app.setLogStartupInfo(false);

        // Arrancamos
        app.run(args);
    }

    /**
     * Elimina todos los archivos .log de la carpeta logs/ al arrancar.
     */
    private static void limpiarDirectorioLogs() {
        try {
            File carpetaLogs = new File("logs");
            if (carpetaLogs.exists() && carpetaLogs.isDirectory()) {
                File[] archivos = carpetaLogs.listFiles();
                if (archivos != null) {
                    for (File archivo : archivos) {
                        if (archivo.isFile() && archivo.getName().endsWith(".log")) {
                            archivo.delete();
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error al limpiar directorio de logs: " + e.getMessage());
        }
    }

    private static void configurarPropiedadesSistema(Dotenv dotenv) {
        String dbUrl = dotenv.get("DB_URL");
        String dbUser = dotenv.get("DB_USER", "root");
        String dbPass = dotenv.get("DB_PASSWORD", "");

        if (dbUrl != null) {
            System.setProperty("spring.datasource.url", dbUrl);
            System.setProperty("spring.datasource.username", dbUser);
            System.setProperty("spring.datasource.password", dbPass);
            System.setProperty("spring.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver");
        }

        // Configuraciones extra de Hibernate para que no ensucie la consola
        System.setProperty("spring.jpa.hibernate.ddl-auto", "update");
        System.setProperty("spring.jpa.show-sql", "false"); // Importante: APAGAR SQL
        System.setProperty("spring.jpa.properties.hibernate.format_sql", "false");
        // Si quieres ver los logs del log.info descomenta esto
        // System.setProperty("logging.level.com.bottrading", "INFO");
    }
}