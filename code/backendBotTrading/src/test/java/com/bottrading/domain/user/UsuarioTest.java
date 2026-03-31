package com.bottrading.domain.user;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * TEST DOMINIO - Usuario
 * 
 * Características:
 *  • SIN @SpringBootTest → Sin contexto Spring
 *  • SIN @Mock → Solo JUnit 5 puro
 *  • Testea: validaciones, comportamiento, invariantes
 * 
 * Flujo de test:
 *   Given (setup)
 *   When (acción)
 *   Then (verificación con hamcrest/assertions)
 */
@DisplayName("Domain Entity - Usuario")
class UsuarioTest {

    @Test
    @DisplayName("✓ Debe crear usuario válido cuando nombre y passwordHash son válidos")
    void should_create_valid_usuario_when_inputs_are_valid() {
        // Given
        String nombre = "juan.perez";
        String passwordHash = "hash_seguro_12345";

        // When
        Usuario usuario = new Usuario(nombre, passwordHash);

        // Then
        assertThat(usuario.getNombre(), is(equalTo(nombre)));
        assertThat(usuario.getPasswordHash(), is(equalTo(passwordHash)));
        assertThat(usuario.esValido(), is(true));
    }

    @Test
    @DisplayName("✗ Debe lanzar IllegalArgumentException cuando nombre es nulo")
    void should_throw_when_nombre_is_null() {
        // Given
        String nombre = null;
        String passwordHash = "hash_seguro";

        // When & Then
        try {
            new Usuario(nombre, passwordHash);
            throw new AssertionError("Debería haber lanzado IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage(), containsString("nombre no puede ser nulo"));
        }
    }

    @Test
    @DisplayName("✗ Debe lanzar IllegalArgumentException cuando nombre está vacío")
    void should_throw_when_nombre_is_empty() {
        // Given
        String nombre = "";
        String passwordHash = "hash_seguro";

        // When & Then
        try {
            new Usuario(nombre, passwordHash);
            throw new AssertionError("Debería haber lanzado IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage(), containsString("nombre no puede ser nulo"));
        }
    }

    @Test
    @DisplayName("✗ Debe lanzar IllegalArgumentException cuando passwordHash es nulo")
    void should_throw_when_passwordHash_is_null() {
        // Given
        String nombre = "usuario";
        String passwordHash = null;

        // When & Then
        try {
            new Usuario(nombre, passwordHash);
            throw new AssertionError("Debería haber lanzado IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage(), containsString("passwordHash no puede ser nulo"));
        }
    }

    @Test
    @DisplayName("✗ Debe lanzar IllegalArgumentException cuando passwordHash está vacío")
    void should_throw_when_passwordHash_is_empty() {
        // Given
        String nombre = "usuario";
        String passwordHash = "";

        // When & Then
        try {
            new Usuario(nombre, passwordHash);
            throw new AssertionError("Debería haber lanzado IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage(), containsString("passwordHash no puede ser nulo"));
        }
    }

    @Test
    @DisplayName("✓ Dos usuarios con mismo nombre y password deben ser iguales (equals)")
    void should_be_equal_when_same_nombre_and_password() {
        // Given
        Usuario usuario1 = new Usuario("juan", "hash123");
        Usuario usuario2 = new Usuario("juan", "hash123");

        // When & Then
        assertThat(usuario1, is(equalTo(usuario2)));
        assertThat(usuario1.hashCode(), is(equalTo(usuario2.hashCode())));
    }

    @Test
    @DisplayName("✓ Usuarios con diferente nombre deben ser distintos")
    void should_not_be_equal_when_different_nombre() {
        // Given
        Usuario usuario1 = new Usuario("juan", "hash123");
        Usuario usuario2 = new Usuario("pedro", "hash123");

        // When & Then
        assertThat(usuario1, not(equalTo(usuario2)));
    }

    @Test
    @DisplayName("✓ esValido() retorna true para usuario válido")
    void should_be_valid_when_all_fields_present() {
        // Given
        Usuario usuario = new Usuario("usuario", "password");

        // When & Then
        assertThat(usuario.esValido(), is(true));
    }
}
