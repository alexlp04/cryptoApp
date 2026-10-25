package db.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class V5VelaUnicaTest {

    private Connection nuevaBase(String nombre, boolean conClave) throws SQLException {
        Connection c = DriverManager.getConnection("jdbc:h2:mem:" + nombre + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        try (Statement st = c.createStatement()) {
            st.execute("CREATE TABLE vela (id BIGINT AUTO_INCREMENT PRIMARY KEY, symbol VARCHAR(10) NOT NULL, "
                    + "time_interval VARCHAR(10) NOT NULL, open_time BIGINT NOT NULL"
                    + (conClave ? ", UNIQUE KEY uk_vela_lookup (symbol, time_interval, open_time)" : "") + ")");
        }
        return c;
    }

    private void migrar(Connection c) throws Exception {
        Context ctx = mock(Context.class);
        when(ctx.getConnection()).thenReturn(c);
        new V5__vela_unica().migrate(ctx);
    }

    private long contar(Connection c) throws SQLException {
        try (Statement st = c.createStatement(); var rs = st.executeQuery("SELECT COUNT(*) FROM vela")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    @DisplayName("Una base antigua con duplicados los depura (conserva el menor id) y recibe la clave única")
    void old_database_is_deduplicated_and_gets_the_unique_key() throws Exception {
        try (Connection c = nuevaBase("v5_old", false); Statement st = c.createStatement()) {
            st.execute("INSERT INTO vela (symbol, time_interval, open_time) VALUES "
                    + "('BTCUSDT','30m',1000),('BTCUSDT','30m',1000),('BTCUSDT','30m',2000),('ETHUSDT','30m',1000)");

            migrar(c);

            assertEquals(3, contar(c));
            assertTrue(V5__vela_unica.tieneClaveUnica(c));
            assertThrows(SQLException.class, () -> st.execute(
                    "INSERT INTO vela (symbol, time_interval, open_time) VALUES ('BTCUSDT','30m',2000)"));
        }
    }

    @Test
    @DisplayName("Una base que ya tiene la clave no se toca (idempotente)")
    void database_with_the_key_is_left_untouched() throws Exception {
        try (Connection c = nuevaBase("v5_new", true); Statement st = c.createStatement()) {
            st.execute("INSERT INTO vela (symbol, time_interval, open_time) VALUES ('BTCUSDT','1h',1)");

            assertTrue(V5__vela_unica.tieneClaveUnica(c));
            migrar(c);

            assertEquals(1, contar(c));
        }
    }

    @Test
    @DisplayName("Sin clave única la detección da falso")
    void detects_the_missing_key() throws Exception {
        try (Connection c = nuevaBase("v5_none", false)) {
            assertFalse(V5__vela_unica.tieneClaveUnica(c));
        }
    }
}
