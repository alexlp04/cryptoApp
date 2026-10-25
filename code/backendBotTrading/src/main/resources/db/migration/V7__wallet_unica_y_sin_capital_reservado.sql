-- Integridad del libro de wallets (issue #95).
--
-- 1) Una wallet por (usuario, nombre): crearWallet hacía existsBy + save, y un duplicado hacía fallar
--    findByUsuarioIdAndNombre con IncorrectResultSizeDataAccessException. Se comprobó antes que la base
--    real no tiene duplicados; si los hubiera, esta migración fallaría al arrancar y habría que depurarlos.
-- 2) La tabla capital_reservado no la usaba ningún servicio (duplicaba campos de instancia_estrategia).

ALTER TABLE wallet ADD CONSTRAINT uk_wallet_usuario_nombre UNIQUE (usuario_id, nombre);

DROP TABLE IF EXISTS capital_reservado;
