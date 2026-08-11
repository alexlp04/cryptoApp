package com.bottrading.shared.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Igual que {@link SchemaMigrationValidationTest} pero contra MySQL 8 real.
 *
 * Cubre lo que H2 en modo compatibilidad no puede garantizar: tipos nativos
 * (BIT(1), DATETIME(6), DECIMAL), semántica de AUTO_INCREMENT y claves foráneas.
 *
 * El perfil se fija con @ActiveProfiles y no por línea de comandos: la anotación
 * tiene prioridad sobre -Dspring.profiles.active, de modo que intentar cambiarlo
 * desde fuera dejaría el test corriendo silenciosamente contra H2.
 *
 * Requiere una base dedicada y credenciales por entorno:
 *   CREATE DATABASE bottradingdb_test;
 *   GRANT ALL ON bottradingdb_test.* TO 'tu_usuario'@'localhost';
 *
 * Ejecutar:
 *   mvn test "-Dtest=SchemaMigrationMySqlIntegrationTest" "-Dintegration.db=true"
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("integration")
@EnabledIfSystemProperty(named = "integration.db", matches = "true")
class SchemaMigrationMySqlIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("Corre realmente contra MySQL, no contra H2")
    void elMotorEsMysql() {
        String producto = jdbc.queryForObject("SELECT @@version_comment", String.class);
        String version = jdbc.queryForObject("SELECT VERSION()", String.class);

        assertTrue(version != null && !version.isBlank(), "sin versión de servidor");
        assertTrue(producto != null && producto.toLowerCase().contains("mysql"),
                "Se esperaba MySQL y el servidor dice: " + producto);
    }

    @Test
    @DisplayName("Flyway crea todas las tablas del dominio en MySQL")
    void migracionesCreanElEsquema() {
        List<String> tablas = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()",
                String.class);

        for (String esperada : List.of("usuario", "vela", "wallet", "instancia_estrategia",
                "instancia_simbolos", "posicion", "ledger_entry",
                "indicador_tecnico", "capital_reservado")) {
            assertTrue(tablas.stream().anyMatch(t -> t.equalsIgnoreCase(esperada)),
                    "Falta la tabla " + esperada + " en MySQL");
        }
    }

    @Test
    @DisplayName("Las columnas de cierre de posición existen con tipo DECIMAL")
    void columnasDeCierreConTipoCorrecto() {
        // Estas tres faltaban en info/tablas.sql; sin ellas no se puede registrar
        // el resultado de una operación cerrada.
        Integer encontradas = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = 'posicion' "
                        + "AND column_name IN ('precio_salida', 'pnl', 'fecha_cierre')",
                Integer.class);

        assertEquals(3, encontradas, "Faltan columnas de cierre en posicion");
    }
}
