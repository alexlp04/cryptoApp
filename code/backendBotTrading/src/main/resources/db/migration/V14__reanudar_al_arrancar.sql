-- Qué estrategias debe reanudar sola la aplicación al arrancar (operación 24/7): las que estaban en marcha cuando
-- se apagó o cayó. Se marca al activar y al reanudar, y se limpia al pausar a mano ('stop'); el apagado de la
-- aplicación pausa sin tocarla. Las filas existentes quedan sin marcar: nada se reanuda solo por sorpresa.
ALTER TABLE instancia_estrategia ADD COLUMN reanudar_al_arrancar BOOLEAN NOT NULL DEFAULT FALSE;
