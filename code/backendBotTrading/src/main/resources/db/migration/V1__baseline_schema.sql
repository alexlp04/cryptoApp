-- Baseline del esquema de CryptoApp.
-- Derivado de info/tablas.sql, omitiendo deliberadamente el bloque
-- DROP TABLE del original: una migración nunca debe destruir datos existentes.

CREATE TABLE IF NOT EXISTS usuario (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    nombre VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    fecha_creacion DATETIME(6),
    eliminado BIT(1) NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS vela (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    symbol VARCHAR(10) NOT NULL,
    time_interval VARCHAR(10) NOT NULL,
    open_time BIGINT NOT NULL,
    close_time BIGINT NOT NULL,
    open DECIMAL(18, 8),
    high DECIMAL(18, 8),
    low DECIMAL(18, 8),
    close DECIMAL(18, 8),
    volume DECIMAL(18, 8),
    quote_volume DECIMAL(20, 8),
    trades INT,
    taker_base_volume DECIMAL(18, 8),
    taker_quote_volume DECIMAL(20, 8),
    fecha_creacion DATETIME(6),
    eliminado BIT(1) NOT NULL DEFAULT 0,
    UNIQUE KEY uk_vela_lookup (symbol, time_interval, open_time)
);

CREATE TABLE IF NOT EXISTS wallet (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    nombre VARCHAR(255) NOT NULL,
    usuario_id BIGINT NOT NULL,
    balance_real DECIMAL(18, 8) NOT NULL,
    balance_disponible DECIMAL(18, 8) NOT NULL,
    type VARCHAR(50),
    is_active BIT(1),
    fecha_creacion DATETIME(6),
    eliminado BIT(1) NOT NULL DEFAULT 0,
    CONSTRAINT fk_wallet_usuario FOREIGN KEY (usuario_id) REFERENCES usuario(id)
);

CREATE TABLE IF NOT EXISTS instancia_estrategia (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    nombre_estrategia VARCHAR(255),
    -- Ausente en info/tablas.sql: ddl-auto=update la creaba en silencio y la
    -- divergencia pasaba inadvertida. La detectó SchemaMigrationValidationTest.
    nombre_modelo VARCHAR(100),
    wallet_asociada BIGINT,
    timeframe VARCHAR(20),
    capital_asignado DECIMAL(18, 8),
    risk_per_trade DECIMAL(18, 8),
    capital_reservado DECIMAL(18, 8),
    capital_comprometido DECIMAL(18, 8),
    riesgo_abierto DECIMAL(18, 8),
    es_real BIT(1),
    estado VARCHAR(50),
    fecha_creacion DATETIME(6),
    eliminado BIT(1) NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS instancia_simbolos (
    instancia_id BIGINT NOT NULL,
    simbolo VARCHAR(255),
    CONSTRAINT fk_simbolos_instancia
        FOREIGN KEY (instancia_id)
        REFERENCES instancia_estrategia(id)
);

CREATE TABLE IF NOT EXISTS posicion (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    simbolo VARCHAR(20),
    precio_entrada DECIMAL(18, 8),
    margen_invertido DECIMAL(18, 8),
    abierta BIT(1) DEFAULT 1,
    -- Datos de cierre de la operación. Ausentes en info/tablas.sql: quien creara la
    -- base desde ese fichero no podría registrar el resultado de una posición cerrada.
    precio_salida DECIMAL(20, 8),
    pnl DECIMAL(20, 8),
    fecha_cierre DATETIME(6),
    instancia_id BIGINT,
    fecha_creacion DATETIME(6),
    eliminado BIT(1) NOT NULL DEFAULT 0,
    CONSTRAINT fk_posicion_instancia
        FOREIGN KEY (instancia_id)
        REFERENCES instancia_estrategia(id)
);

CREATE TABLE IF NOT EXISTS ledger_entry (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    wallet_id BIGINT,
    estrategia_id BIGINT,
    type VARCHAR(50),
    amount DECIMAL(18, 8),
    balance_after DECIMAL(18, 8),
    reference VARCHAR(255),
    timestamp DATETIME(6),
    fecha_creacion DATETIME(6),
    eliminado BIT(1) NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS indicador_tecnico (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    vela_id BIGINT NOT NULL,
    tipo VARCHAR(50) NOT NULL,
    parametros VARCHAR(100),
    valor DECIMAL(20, 8) NOT NULL,
    fecha_creacion DATETIME(6),
    eliminado BIT(1) NOT NULL DEFAULT 0,
    CONSTRAINT fk_indicador_vela FOREIGN KEY (vela_id) REFERENCES vela(id)
);

CREATE TABLE IF NOT EXISTS capital_reservado (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    wallet_id BIGINT,
    estrategia_id BIGINT,
    reservado DECIMAL(18, 8),
    comprometido DECIMAL(18, 8),
    riesgo_abierto DECIMAL(18, 8),
    fecha_creacion DATETIME(6),
    eliminado BIT(1) NOT NULL DEFAULT 0
);
