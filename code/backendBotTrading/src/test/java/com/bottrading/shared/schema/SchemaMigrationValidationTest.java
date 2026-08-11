package com.bottrading.shared.schema;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Verifica que las migraciones de Flyway y las entidades JPA no han divergido.
 *
 * El perfil 'schema' arranca con ddl-auto=validate sobre un esquema creado
 * exclusivamente por Flyway. Si una entidad declara una columna que la migración no
 * crea (o con otro tipo), el contexto no levanta y este test falla.
 *
 * Los tests unitarios normales no pueden detectar esto: generan el esquema desde las
 * propias entidades, con lo que la correspondencia es cierta por construcción.
 *
 * Se usa @DataJpaTest en lugar de @SpringBootTest porque AppBot implementa
 * CommandLineRunner y arrancaría la CLI interactiva, colgando la suite.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("schema")
class SchemaMigrationValidationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("Las entidades JPA validan contra el esquema creado por Flyway")
    void entidadesCuadranConLasMigraciones() {
        // Llegar aquí ya implica que ddl-auto=validate pasó durante el arranque.
        Integer tablas = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = SCHEMA()",
                Integer.class);

        assertTrue(tablas != null && tablas > 0, "Flyway no creó ninguna tabla");
    }

    @Test
    @DisplayName("Flyway registra la migración baseline como aplicada")
    void baselineQuedaRegistrada() {
        Integer aplicadas = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = TRUE",
                Integer.class);

        assertTrue(aplicadas != null && aplicadas >= 1,
                "No hay migraciones aplicadas en flyway_schema_history");
    }
}
