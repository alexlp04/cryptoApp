-- Cola durable de señales pendientes de reintento (C4, issue #18).
--
-- La entidad SenalFallidaPendiente llegó en la rama fix/c4-durable-retry-queue sin su
-- migración, y SchemaMigrationValidationTest lo detectó al integrar:
--   Schema-validation: missing table [senal_fallida_pendiente]
-- Esta migración cierra ese hueco.
--
-- Los tipos siguen la convención de V1__baseline_schema.sql: DATETIME(6) para instantes,
-- BIT(1) para booleanos y DECIMAL para importes (nunca DOUBLE).

CREATE TABLE IF NOT EXISTS senal_fallida_pendiente (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,

    instancia_id BIGINT NOT NULL,

    -- Clave determinista `symbol|timeframe|action|timestamp`: es lo que permite
    -- reconocer una señal ya aplicada y no duplicarla al drenar la cola.
    clave_senal VARCHAR(160) NOT NULL,

    symbol VARCHAR(255),
    action VARCHAR(255),
    price DECIMAL(20, 8),
    timeframe VARCHAR(255),
    signal_timestamp BIGINT NOT NULL,
    real_signal BIT(1) NOT NULL DEFAULT 0,

    -- Campos heredados de BaseEntity (soft-delete).
    fecha_creacion DATETIME(6),
    eliminado BIT(1) NOT NULL DEFAULT 0
);

-- El drenado busca las pendientes de una instancia concreta descartando las ya aplicadas.
CREATE INDEX idx_senal_fallida_instancia_eliminado
    ON senal_fallida_pendiente (instancia_id, eliminado);

-- La comprobación de idempotencia va por clave; sin índice degrada a full scan según
-- crece la cola.
CREATE INDEX idx_senal_fallida_clave
    ON senal_fallida_pendiente (clave_senal);
