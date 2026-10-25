package com.bottrading.user.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UsuarioMapperTest {

    private final UsuarioMapper mapper = new UsuarioMapper();

    @Test
    @DisplayName("Re-guardar un usuario conserva fecha de creación y baja lógica (#112)")
    void round_trip_keeps_creation_date_and_soft_delete() {
        UsuarioJpaEntity entity = new UsuarioJpaEntity("alex", "$2a$hash");
        entity.setId(7L);
        entity.setFechaCreacion(Instant.parse("2024-01-01T00:00:00Z"));
        entity.setEliminado(true);

        UsuarioJpaEntity back = mapper.toJpa(mapper.toDomain(entity));

        assertEquals(Instant.parse("2024-01-01T00:00:00Z"), back.getFechaCreacion());
        assertTrue(back.isEliminado(), "una baja no debe reactivarse al re-guardar");
        assertEquals(7L, back.getId());
    }
}
