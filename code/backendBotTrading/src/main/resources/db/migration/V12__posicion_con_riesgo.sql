-- Gestión de riesgo como en una plataforma de trading: cada posición guarda las unidades compradas,
-- su stop y su objetivo, lo que arriesga en dinero y las comisiones pagadas. Las posiciones anteriores
-- quedan con NULL y se cierran con la fórmula antigua (margen * variación / entrada).

ALTER TABLE posicion ADD COLUMN unidades DECIMAL(24, 8) NULL;
ALTER TABLE posicion ADD COLUMN stop_loss DECIMAL(20, 8) NULL;
ALTER TABLE posicion ADD COLUMN take_profit DECIMAL(20, 8) NULL;
ALTER TABLE posicion ADD COLUMN riesgo DECIMAL(18, 8) NULL;
ALTER TABLE posicion ADD COLUMN comisiones DECIMAL(18, 8) NULL;
