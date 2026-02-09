package com.bottrading;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import com.bottrading.utils.AppConstants;
import com.bottrading.utils.ConsoleLoader;

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

        configurarPropiedadesSistema(dotenv);
        
        // 2. CONFIGURACIÓN DEL SILENCIO (Nuclear)
        // Forzamos que el nivel de log sea ERROR antes de que Spring arranque
        System.setProperty("logging.level.root", AppConstants.KEY_ERROR);
        System.setProperty("logging.level.org.springframework", AppConstants.KEY_ERROR);
        System.setProperty("logging.level.com.bottrading", AppConstants.KEY_ERROR);
        // Desactivamos el banner por propiedad también
        System.setProperty("spring.main.banner-mode", "off"); 

        // 3. ARRANQUE PERSONALIZADO
        SpringApplication app = new SpringApplication(BotApplication.class);
        
        // Apagamos el Banner visual
        app.setBannerMode(Banner.Mode.OFF);
        // Apagamos el log de "Starting BotApplication..."
        app.setLogStartupInfo(false);
        
        // Arrancamos
        app.run(args);
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
        
        // Configuraciones extra de Hibernate para que no hable
        System.setProperty("spring.jpa.hibernate.ddl-auto", "update");
        System.setProperty("spring.jpa.show-sql", "false"); // Importante: APAGAR SQL
        System.setProperty("spring.jpa.properties.hibernate.format_sql", "false");
    }
}