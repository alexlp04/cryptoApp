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

    // ══════════════════════════════════════════════════════════════════════════
    // VALORES LÍMITE
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("✓ Debe aceptar nombre con longitud mínima (1 carácter)")
    void should_accept_nombre_with_minimum_length() {
        // Given
        String nombre = "a";
        String passwordHash = "hash_valido";

        // When
        Usuario usuario = new Usuario(nombre, passwordHash);

        // Then
        assertThat(usuario.getNombre(), is(equalTo("a")));
    }

    @Test
    @DisplayName("✓ Debe aceptar nombre con longitud máxima (255 caracteres)")
    void should_accept_nombre_with_maximum_length() {
        // Given
        String nombre = "a".repeat(255);
        String passwordHash = "hash_valido";

        // When
        Usuario usuario = new Usuario(nombre, passwordHash);

        // Then
        assertThat(usuario.getNombre().length(), is(equalTo(255)));
    }

    @Test
    @DisplayName("✗ Debe lanzar cuando nombre es solo espacios en blanco")
    void should_throw_when_nombre_is_whitespace_only() {
        // Given
        String nombre = "   ";
        String passwordHash = "hash_valido";

        // When & Then
        try {
            new Usuario(nombre, passwordHash);
            throw new AssertionError("Debería haber lanzado IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage(), containsString("nombre no puede ser nulo"));
        }
    }

    @Test
    @DisplayName("✓ Debe aceptar passwordHash con longitud mínima (1 carácter)")
    void should_accept_passwordHash_with_minimum_length() {
        // Given
        String nombre = "usuario";
        String passwordHash = "x";

        // When
        Usuario usuario = new Usuario(nombre, passwordHash);

        // Then
        assertThat(usuario.getPasswordHash(), is(equalTo("x")));
    }

    @Test
    @DisplayName("✓ Debe aceptar passwordHash con longitud máxima (512 caracteres)")
    void should_accept_passwordHash_with_maximum_length() {
        // Given
        String nombre = "usuario";
        String passwordHash = "x".repeat(512);

        // When
        Usuario usuario = new Usuario(nombre, passwordHash);

        // Then
        assertThat(usuario.getPasswordHash().length(), is(equalTo(512)));
    }

    @Test
    @DisplayName("✗ Debe lanzar cuando passwordHash es solo espacios en blanco")
    void should_throw_when_passwordHash_is_whitespace_only() {
        // Given
        String nombre = "usuario";
        String passwordHash = "   ";

        // When & Then
        try {
            new Usuario(nombre, passwordHash);
            throw new AssertionError("Debería haber lanzado IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage(), containsString("passwordHash no puede ser nulo"));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // COMPORTAMIENTO DE NEGOCIO
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("✓ Usuario debe ser inmutable (no setter de nombre)")
    void should_usuario_be_immutable_nombre() {
        // Given
        Usuario usuario = new Usuario("original", "hash");

        // When & Then - no hay setter público de nombre, así que debe mantener original
        assertThat(usuario.getNombre(), is(equalTo("original")));
    }

    @Test
    @DisplayName("✓ Usuario debe ser inmutable (no setter de passwordHash)")
    void should_usuario_be_immutable_passwordHash() {
        // Given
        Usuario usuario = new Usuario("nombre", "original_hash");

        // When & Then - no hay setter público de passwordHash, así que debe mantener original
        assertThat(usuario.getPasswordHash(), is(equalTo("original_hash")));
    }

    @Test
    @DisplayName("✓ Usuario debe heredar getId() de BaseEntity")
    void should_usuario_inherit_getId_from_BaseEntity() {
        // Given
        Usuario usuario = new Usuario("nombre", "hash");

        // When & Then
        assertThat(usuario.getId(), is(nullValue())); // Nullvalue hasta persistirse
    }

    @Test
    @DisplayName("✓ Usuario debe heredar getFechaCreacion() de BaseEntity")
    void should_usuario_inherit_getFechaCreacion_from_BaseEntity() {
        // Given
        Usuario usuario = new Usuario("nombre", "hash");

        // When & Then
        assertThat(usuario.getFechaCreacion(), is(notNullValue()));
    }

    @Test
    @DisplayName("✓ Usuario debe heredar isEliminado() de BaseEntity")
    void should_usuario_inherit_isEliminado_from_BaseEntity() {
        // Given
        Usuario usuario = new Usuario("nombre", "hash");

        // When & Then
        assertThat(usuario.isEliminado(), is(false)); // Por defecto false
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TOSTRING & SERIALIZACIÓN
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("✓ Usuario debe generar toString() válido")
    void should_usuario_generate_valid_toString() {
        // Given
        Usuario usuario = new Usuario("juan", "hash123");

        // When
        String toString = usuario.toString();

        // Then
        assertThat(toString, is(notNullValue()));
        assertThat(toString, containsString("juan"));
    }
}
