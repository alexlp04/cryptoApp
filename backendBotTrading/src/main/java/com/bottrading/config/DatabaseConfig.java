package com.bottrading.config;

import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import javax.sql.DataSource;

@Configuration
public class DatabaseConfig {

    @Bean
    public DataSource dataSource() {
        // Leemos directamente del System.getProperty que llenamos en el Main
        String url = System.getProperty("DB_URL");
        String user = System.getProperty("DB_USER");
        String pass = System.getProperty("DB_PASSWORD");

        return DataSourceBuilder.create()
                .url(url)
                .username(user)
                .password(pass)
                .driverClassName("com.mysql.cj.jdbc.Driver")
                .build();
    }
}