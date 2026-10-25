-- Límite de intentos de login (issue #107): tras 5 fallos seguidos la cuenta se bloquea un rato.
-- Se guarda en la BBDD y no en memoria para que reiniciar la aplicación no reinicie el contador.

ALTER TABLE usuario ADD COLUMN intentos_fallidos INT NOT NULL DEFAULT 0;
ALTER TABLE usuario ADD COLUMN bloqueado_hasta DATETIME(6) NULL;
