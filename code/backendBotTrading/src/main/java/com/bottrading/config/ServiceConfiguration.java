package com.bottrading.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.bottrading.backtesting.infrastructure.StatsCsvRepository;
import com.bottrading.trading.infrastructure.cache.StatsCache;

/**
 * Configuración de Spring Beans para servicios de soporte.
 */
@Configuration
@EnableScheduling
public class ServiceConfiguration {

    /**
     * Crea el bean StatsCache que será inyectado en los servicios.
     */
    @Bean
    public StatsCache statsCache(@Autowired StatsCsvRepository statsCsvRepository) {
        return new StatsCache(statsCsvRepository);
    }
}
