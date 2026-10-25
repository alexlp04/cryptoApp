-- Las posiciones pueden ser largas o cortas (simuladas, sin apalancamiento). Las existentes son largas.
ALTER TABLE posicion ADD COLUMN lado VARCHAR(5) NOT NULL DEFAULT 'LONG';
