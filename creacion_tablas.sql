SET FOREIGN_KEY_CHECKS = 0;
DROP TABLE IF EXISTS  indicador_tecnico, ledger_entry, posicion, capital_reservado, instancia_estrategia, wallet, vela, usuario;
SET FOREIGN_KEY_CHECKS = 1;

-- 1. USUARIO
CREATE TABLE usuario (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    nombre VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    fecha_creacion DATETIME(6),
    eliminado BIT(1) NOT NULL DEFAULT 0
);

-- 2. VELA
CREATE TABLE vela (
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

-- 3. WALLET
CREATE TABLE wallet (
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

-- 4. INSTANCIAS ESTRATEGIA
CREATE TABLE instancia_estrategia (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    nombre_estrategia VARCHAR(255),
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

-- 5. INSTANCIA SIMBOLOS (Tabla intermedia para la List<String>)
CREATE TABLE instancia_simbolos (
    instancia_id BIGINT NOT NULL,
    simbolo VARCHAR(20),
    CONSTRAINT fk_simbolos_instancia FOREIGN KEY (instancia_id) REFERENCES instancias_estrategia(id)
);
USE bottradingdb;
CREATE TABLE instancia_simbolos (
    instancia_id BIGINT NOT NULL,
    simbolo VARCHAR(255),
    CONSTRAINT fk_instancia_estrategia 
        FOREIGN KEY (instancia_id) 
        REFERENCES instancia_estrategia (id) -- Quitada la 's'
);

-- 6. POSICIONES (Corregida la referencia FK)
CREATE TABLE posicion (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    simbolo VARCHAR(20),
    precio_entrada DECIMAL(18, 8),
    margen_invertido DECIMAL(18, 8),
    abierta BIT(1) DEFAULT 1,
    instancia_id BIGINT,
    fecha_creacion DATETIME(6),
    eliminado BIT(1) NOT NULL DEFAULT 0,
    CONSTRAINT fk_posicion_instancia 
        FOREIGN KEY (instancia_id) 
        REFERENCES instancia_estrategia (id) -- Quitada la 's'
);

-- 7. LEDGER ENTRIES
CREATE TABLE ledger_entry (
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

-- 8. INDICADOR TECNICO
CREATE TABLE indicador_tecnico (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    vela_id BIGINT NOT NULL,
    tipo VARCHAR(50) NOT NULL,
    parametros VARCHAR(100),
    valor DECIMAL(20, 8) NOT NULL,
    fecha_creacion DATETIME(6),
    eliminado BIT(1) NOT NULL DEFAULT 0,
    CONSTRAINT fk_indicador_vela FOREIGN KEY (vela_id) REFERENCES vela(id)
);

-- 9. CAPITAL RESERVADO
CREATE TABLE capital_reservado (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    wallet_id BIGINT,
    estrategia_id BIGINT,
    reservado DECIMAL(18, 8),
    comprometido DECIMAL(18, 8),
    riesgo_abierto DECIMAL(18, 8),
    fecha_creacion DATETIME(6),
    eliminado BIT(1) NOT NULL DEFAULT 0
);
