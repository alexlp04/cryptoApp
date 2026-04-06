package com.bottrading.config;

import com.bottrading.backtesting.infrastructure.FileService;
import com.bottrading.trading.infrastructure.cache.StatsCache;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuración de Spring Beans para servicios de soporte.
 */
@Configuration
public class ServiceConfiguration {

    /**
     * Crea el bean StatsCache que será inyectado en los servicios.
     */
    @Bean
    public StatsCache statsCache(@Autowired FileService fileService) {
        return new StatsCache(fileService);
    }
}
