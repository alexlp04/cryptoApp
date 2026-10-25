package com.bottrading.user.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
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

    private Usuario conPassword(String password) {
        Usuario u = new Usuario("alex", com.bottrading.shared.utils.HashUtils.hashPassword(password));
        when(repo.findByNombreAndEliminadoFalse("alex")).thenReturn(Optional.of(u));
        return u;
    }

    @Test
    @DisplayName("Cinco fallos seguidos bloquean la cuenta y la contraseña correcta deja de valer (#107)")
    void five_failures_lock_the_account() {
        Usuario u = conPassword("s3creta!");

        for (int i = 0; i < Usuario.MAX_INTENTOS; i++) {
            assertFalse(service.validarCredenciales("alex", "mala"));
        }

        assertTrue(u.estaBloqueado(Instant.now()));
        ValidationException e = assertThrows(ValidationException.class,
                () -> service.validarCredenciales("alex", "s3creta!"));
        assertTrue(e.getMessage().contains("bloqueada"));
        verify(repo, times(Usuario.MAX_INTENTOS)).save(u);
    }

    @Test
    @DisplayName("Un acierto antes del límite reinicia el contador")
    void success_resets_the_counter() {
        Usuario u = conPassword("s3creta!");

        service.validarCredenciales("alex", "mala");
        service.validarCredenciales("alex", "mala");
        assertEquals(2, u.getIntentosFallidos());
        assertTrue(service.validarCredenciales("alex", "s3creta!"));

        assertEquals(0, u.getIntentosFallidos());
    }

    @Test
    @DisplayName("Pasado el tiempo de bloqueo se puede entrar de nuevo")
    void lock_expires() {
        Usuario u = conPassword("s3creta!");
        u.restaurarBloqueo(0, Instant.now().minusSeconds(1));

        assertTrue(service.validarCredenciales("alex", "s3creta!"));
        assertEquals(null, u.getBloqueadoHasta());
    }

    @Test
    @DisplayName("Un usuario inexistente devuelve false sin guardar nada")
    void unknown_user_is_rejected() {
        when(repo.findByNombreAndEliminadoFalse("nadie")).thenReturn(Optional.empty());

        assertFalse(service.validarCredenciales("nadie", "x"));
        verify(repo, never()).save(any());
    }
}
