-- Índices para las consultas del libro mayor por wallet y por estrategia (issue #95).
-- No se añade UNIQUE (usuario_id, nombre) a wallet: una base con duplicados previos haría fallar la
-- migración al arrancar; requiere limpiar datos antes.

CREATE INDEX idx_ledger_entry_wallet ON ledger_entry (wallet_id);
CREATE INDEX idx_ledger_entry_estrategia ON ledger_entry (estrategia_id);
