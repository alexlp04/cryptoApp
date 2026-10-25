package com.bottrading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

class ConfigDotenvTest {

    @Configuration
    static class Vacia {
    }

    @TempDir
    Path tmp;

    private ConfigurableApplicationContext arrancar(String... extra) {
        String[] base = {
            // no cargar el application.properties real: importaría el .env de quien ejecute el test
            "spring.config.name=sin-application-properties",
            "spring.datasource.url=${DB_URL}", "spring.datasource.username=${DB_USER}",
            "spring.datasource.password=${DB_PASSWORD}",
        };
        String[] todas = new String[base.length + extra.length];
        System.arraycopy(base, 0, todas, 0, base.length);
        System.arraycopy(extra, 0, todas, base.length, extra.length);
        return new SpringApplicationBuilder(Vacia.class).web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF).properties(todas).run();
    }

    @Test
    @DisplayName("Las credenciales de un .env se resuelven con spring.config.import (#105)")
    void env_file_values_reach_the_datasource_properties() throws Exception {
        Path env = tmp.resolve(".env");
        Files.writeString(env, "DB_URL=jdbc:mysql://localhost:3306/cryptoapp\nDB_USER=bot\nDB_PASSWORD=p4ss=con:signos\n");

        try (ConfigurableApplicationContext ctx = arrancar(
                "spring.config.import=optional:file:" + env.toString().replace('\\', '/') + "[.properties]")) {
            ConfigurableEnvironment e = ctx.getEnvironment();
            assertEquals("jdbc:mysql://localhost:3306/cryptoapp", e.getProperty("spring.datasource.url"));
            assertEquals("bot", e.getProperty("spring.datasource.username"));
            assertEquals("p4ss=con:signos", e.getProperty("spring.datasource.password"));
        }
    }

    @Test
    @DisplayName("Sin .env ni variables, la falta se detecta y se identifica la variable (#105)")
    void missing_variable_is_reported_by_name() {
        try (ConfigurableApplicationContext ctx = arrancar()) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> ctx.getEnvironment().getProperty("spring.datasource.url"));

            assertEquals("DB_URL", BotApplication.variableSinResolver(error));
        }
    }

    @Test
    @DisplayName("variableSinResolver recorre la cadena de causas y devuelve null si no aplica")
    void placeholder_is_found_in_the_cause_chain() {
        Exception raiz = new IllegalArgumentException("Could not resolve placeholder 'DB_PASSWORD' in value \"${DB_PASSWORD}\"");

        assertEquals("DB_PASSWORD", BotApplication.variableSinResolver(new RuntimeException("arranque", raiz)));
        assertNull(BotApplication.variableSinResolver(new RuntimeException("otro fallo")));
    }

    @Test
    @DisplayName("application.properties importa ./.env y toma las credenciales de él")
    void real_properties_wire_the_env_import() throws Exception {
        var props = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"));

        assertTrue(props.getProperty("spring.config.import").contains(".env"));
        assertEquals("${DB_URL}", props.getProperty("spring.datasource.url"));
        assertEquals("${DB_USER}", props.getProperty("spring.datasource.username"));
        assertEquals("${DB_PASSWORD}", props.getProperty("spring.datasource.password"));
    }
}
