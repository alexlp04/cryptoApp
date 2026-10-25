-- Cada estrategia pertenece a un usuario (issue #112): sin esto, con varios usuarios en la misma
-- máquina uno podía parar o liquidar las estrategias de otro. Las existentes heredan el dueño de su wallet.

ALTER TABLE instancia_estrategia ADD COLUMN usuario_id BIGINT NULL;

UPDATE instancia_estrategia
SET usuario_id = (SELECT w.usuario_id FROM wallet w WHERE w.id = instancia_estrategia.wallet_asociada);

CREATE INDEX idx_instancia_usuario ON instancia_estrategia (usuario_id);
