package com.bottrading;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;

@SpringBootApplication
@EntityScan(basePackages = "com.bottrading.beans")
public class BotApplication {

    public static void main(String[] args) {
        // Intentamos cargar el .env desde varias rutas comunes
        Dotenv dotenv = Dotenv.configure()
                .directory("./backendBotTrading")
                .ignoreIfMissing()
                .load();

        // Si no cargó nada en la ruta anterior, intentamos la raíz actual
        if (dotenv.get("DB_URL") == null) {
            dotenv = Dotenv.configure().ignoreIfMissing().load();
        }

        // Inyectar variables y imprimir para depuración (puedes quitar los prints
        // luego)
        System.out.println("--- Cargando variables de entorno ---");
        dotenv.entries().forEach(entry -> {
            System.setProperty(entry.getKey(), entry.getValue());
            System.out.println("Cargada: " + entry.getKey());
        });
        System.out.println("-------------------------------------");

        SpringApplication.run(BotApplication.class, args);
    }
}