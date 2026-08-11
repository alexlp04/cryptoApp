package com.bottrading.shared.schema;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Utilidad de diagnóstico: vuelca el esquema que Hibernate deriva de las entidades.
 *
 * Sirve para localizar de una vez todas las divergencias con las migraciones de Flyway,
 * en lugar de descubrirlas de una en una. Desactivado salvo petición explícita:
 *   mvn test "-Dtest=EntitySchemaDumpTest" "-Ddump.schema=true"
 *
 * Usa @DataJpaTest y no @SpringBootTest a propósito: AppBot implementa CommandLineRunner
 * y arrancaría la CLI interactiva, dejando el test colgado esperando entrada.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EnabledIfSystemProperty(named = "dump.schema", matches = "true")
class EntitySchemaDumpTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void volcarEsquemaDeEntidades() throws Exception {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT table_name, column_name, data_type, character_maximum_length, "
                        + "numeric_precision, numeric_scale, is_nullable "
                        + "FROM information_schema.columns "
                        + "WHERE table_schema = SCHEMA() "
                        + "ORDER BY table_name, column_name");

        List<String> lines = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            lines.add(String.format("%s.%s %s len=%s prec=%s scale=%s null=%s",
                    r.get("TABLE_NAME"), r.get("COLUMN_NAME"), r.get("DATA_TYPE"),
                    r.get("CHARACTER_MAXIMUM_LENGTH"), r.get("NUMERIC_PRECISION"),
                    r.get("NUMERIC_SCALE"), r.get("IS_NULLABLE")));
        }

        Files.write(Path.of("target", "entity-schema-dump.txt"), lines);
    }
}
