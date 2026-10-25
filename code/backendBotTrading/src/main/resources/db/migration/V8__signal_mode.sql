-- Momento en que una estrategia en tiempo real evalúa sus señales (issue #85):
-- 'close' = al cerrar cada vela, 'open' = al abrir la siguiente, 'both' = en ambos momentos.
-- Las estrategias ya existentes pasan a 'close', que es el modo seguro (decide con la vela completa).

ALTER TABLE instancia_estrategia ADD COLUMN signal_mode VARCHAR(10) NOT NULL DEFAULT 'close';
