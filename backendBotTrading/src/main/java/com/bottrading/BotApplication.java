package com.bottrading;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;

import java.nio.file.Files;
import java.nio.file.Paths;

@SpringBootApplication
@EntityScan(basePackages = "com.bottrading.beans")
public class BotApplication {

    public static void main(String[] args) {
        // Lógica para encontrar el .env sea cual sea el directorio de ejecución
        Dotenv dotenv;
        
        // Opción A: Estamos en la raíz (VS Code) -> buscar en ./backendBotTrading/.env
        if (Files.exists(Paths.get("./backendBotTrading/.env"))) {
            System.out.println("Ejecutando desde raíz. Cargando .env de subcarpeta...");
            dotenv = Dotenv.configure()
                    .directory("./backendBotTrading")
                    .load();
        } 
        // Opción B: Estamos dentro de la carpeta (Maven) -> buscar en ./.env
        else {
            System.out.println("Ejecutando desde subcarpeta. Cargando .env local...");
            dotenv = Dotenv.configure()
                    .directory("./")
                    .ignoreIfMissing()
                    .load();
        }

        // Inyectar propiedades a Spring
        System.setProperty("spring.datasource.url", dotenv.get("DB_URL"));
        System.setProperty("spring.datasource.username", dotenv.get("DB_USER"));
        System.setProperty("spring.datasource.password", dotenv.get("DB_PASSWORD"));
        System.setProperty("spring.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver");
        
        // Ajustes JPA
        System.setProperty("spring.jpa.hibernate.ddl-auto", "update");
        System.setProperty("spring.jpa.show-sql", "false");

        SpringApplication.run(BotApplication.class, args);
    }
}