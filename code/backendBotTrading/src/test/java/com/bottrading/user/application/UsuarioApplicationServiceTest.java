package com.bottrading.user.application;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import com.bottrading.shared.exceptions.ValidationException;
import com.bottrading.user.application.port.out.UsuarioRepositoryPort;
import com.bottrading.user.domain.Usuario;

class UsuarioApplicationServiceTest {

    private final UsuarioRepositoryPort repo = mock(UsuarioRepositoryPort.class);
    private final UsuarioApplicationService service = new UsuarioApplicationService(repo);

    @Test
    @DisplayName("Nombres vacíos, cortos, largos o con caracteres raros se rechazan (#107)")
    void invalid_names_are_rejected() {
        for (String nombre : new String[] {null, "", "ab", "x".repeat(33), "con espacio", "ñandú", "a;b"}) {
            assertThrows(ValidationException.class, () -> service.create(nombre, "s3creta!"), "nombre: " + nombre);
        }
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("Una alta simultánea que viola la restricción única da un error legible, no una excepción de BBDD")
    void unique_constraint_violation_becomes_validation_error() {
        when(repo.findByNombreAndEliminadoFalse("alex")).thenReturn(Optional.empty());
        when(repo.save(any(Usuario.class))).thenThrow(new DataIntegrityViolationException("dup"));

        ValidationException e = assertThrows(ValidationException.class, () -> service.create("alex", "s3creta!"));

        assertTrue(e.getMessage().contains("en uso"));
    }
}
