package db.migration;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * Garantiza la clave única (symbol, time_interval, open_time) en {@code vela} (issue #113).
 *
 * <p>V1 usa CREATE TABLE IF NOT EXISTS: una base anterior, creada sin esa clave, nunca la recibió. Sin
 * ella el ON DUPLICATE KEY UPDATE no actuaba (velas duplicadas) y cada consulta era un full scan.
 *
 * <p>Es una migración Java porque "añadir la clave si no existe" no se puede expresar en SQL portable
 * (MySQL no tiene ADD UNIQUE IF NOT EXISTS) y V1 ya la crea en las bases nuevas. En una base con
 * duplicados se conserva la fila de menor id de cada grupo y se borran las demás: ADVERTENCIA, haz copia
 * de seguridad antes del primer arranque con muchos datos (puede tardar en tablas de millones de filas).
 */
public class V5__vela_unica extends BaseJavaMigration {

    private static final Set<String> COLUMNAS = Set.of("symbol", "time_interval", "open_time");

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        if (tieneClaveUnica(connection)) {
            return;
        }
        try (Statement st = connection.createStatement()) {
            // Tabla derivada: MySQL no permite borrar de una tabla leyéndola en una subconsulta directa.
            st.execute("DELETE FROM vela WHERE id NOT IN ("
                    + "SELECT id FROM (SELECT MIN(id) AS id FROM vela GROUP BY symbol, time_interval, open_time) k)");
            st.execute("ALTER TABLE vela ADD CONSTRAINT uk_vela_lookup UNIQUE (symbol, time_interval, open_time)");
        }
    }

    static boolean tieneClaveUnica(Connection connection) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        for (String tabla : new String[] {"vela", "VELA"}) { // MySQL conserva el nombre, H2 lo pone en mayúsculas
            Set<String> columnas = new HashSet<>();
            String indiceActual = null;
            try (ResultSet rs = meta.getIndexInfo(connection.getCatalog(), connection.getSchema(), tabla, true, false)) {
                while (rs.next()) {
                    String indice = rs.getString("INDEX_NAME");
                    String columna = rs.getString("COLUMN_NAME");
                    if (indice == null || columna == null) {
                        continue;
                    }
                    if (!indice.equals(indiceActual)) {
                        if (coincide(columnas)) {
                            return true;
                        }
                        columnas.clear();
                        indiceActual = indice;
                    }
                    columnas.add(columna.toLowerCase());
                }
            }
            if (coincide(columnas)) {
                return true;
            }
        }
        return false;
    }

    private static boolean coincide(Set<String> columnas) {
        return columnas.equals(COLUMNAS);
    }
}
