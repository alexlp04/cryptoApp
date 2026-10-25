-- Importes y precios con 8 decimales (issue #56).
--
-- V1 usa CREATE TABLE IF NOT EXISTS: en una base creada antes por ddl-auto=update las columnas
-- sin @Column(precision, scale) quedaron como DECIMAL(38,2), así que el precio de entrada se
-- truncaba a 2 decimales (0.6234 -> 0.62) y el PnL de activos baratos salía mal. En una base
-- nueva estas sentencias no cambian nada (ya son DECIMAL(18,8)).

-- Una sentencia por columna: H2 (usado en SchemaMigrationValidationTest) no admite varios MODIFY.

ALTER TABLE wallet MODIFY balance_real DECIMAL(18, 8) NOT NULL;
ALTER TABLE wallet MODIFY balance_disponible DECIMAL(18, 8) NOT NULL;
ALTER TABLE instancia_estrategia MODIFY capital_asignado DECIMAL(18, 8);
ALTER TABLE instancia_estrategia MODIFY risk_per_trade DECIMAL(18, 8);
ALTER TABLE instancia_estrategia MODIFY capital_reservado DECIMAL(18, 8);
ALTER TABLE posicion MODIFY precio_entrada DECIMAL(18, 8);
ALTER TABLE posicion MODIFY margen_invertido DECIMAL(18, 8);
ALTER TABLE ledger_entry MODIFY amount DECIMAL(18, 8);
ALTER TABLE ledger_entry MODIFY balance_after DECIMAL(18, 8);
