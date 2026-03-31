package com.bottrading.application.user;

import com.bottrading.application.user.port.out.UsuarioRepositoryPort;
import com.bottrading.domain.user.Usuario;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * TEST APLICACIÓN - UsuarioApplicationService
 * 
 * Características:
 *  • @ExtendWith(MockitoExtension.class) → Sin contexto Spring
 *  • @Mock → Mockear el puerto de salida  
 *  • @InjectMocks → Spring NO es responsable de inyectar
 *  • Testea: orquestación, flujos de casos de uso, validaciones de aplicación
 * 
 * Flujo de test:
 *   1. Given - setup mock
 *   2. When - invocar caso de uso
 *   3. Then - verificar resultado y que se llamó al puerto correctamente
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Application Service - Usuario")
class UsuarioApplicationServiceTest {

    @Mock
    private UsuarioRepositoryPort usuarioRepositoryPort;

    @InjectMocks
    private UsuarioApplicationService service;

    @Test
    @DisplayName("✓ Crear usuario debe persistir y retornar usuario del dominio")
    void should_create_usuario_successfully_when_usuario_does_not_exist() {
        // Given
        String nombre = "juan.perez";
        String passwordHash = "hash_seguro";

        // Mock: el repositorio retorna Optional.empty() para indicar que NO existe
        when(usuarioRepositoryPort.findByNombre(nombre))
            .thenReturn(Optional.empty());

        // Mock: simular guardado y retorno de usuario persistido
        Usuario usuarioPersistido = new Usuario(nombre, passwordHash);
        when(usuarioRepositoryPort.save(any(Usuario.class)))
            .thenReturn(usuarioPersistido);

        // When
        Usuario resultado = service.create(nombre, passwordHash);

        // Then
        assertThat(resultado, notNullValue());
        assertThat(resultado.getNombre(), is(equalTo(nombre)));
        assertThat(resultado.getPasswordHash(), is(equalTo(passwordHash)));

        // Verificar que se consultó el repositorio para evitar duplicados
        verify(usuarioRepositoryPort, times(1)).findByNombre(nombre);
        // Verificar que se guardó
        verify(usuarioRepositoryPort, times(1)).save(any(Usuario.class));
    }

    @Test
    @DisplayName("✗ Crear usuario debe lanzar RuntimeException si usuario ya existe")
    void should_throw_when_usuario_already_exists() {
        // Given
        String nombre = "juan.perez";
        String passwordHash = "hash_seguro";

        // Mock: el repositorio retorna un usuario existente
        Usuario usuarioExistente = new Usuario(nombre, "otro_hash");
        when(usuarioRepositoryPort.findByNombre(nombre))
            .thenReturn(Optional.of(usuarioExistente));

        // When & Then
        try {
            service.create(nombre, passwordHash);
            throw new AssertionError("Debería haber lanzado RuntimeException");
        } catch (RuntimeException e) {
            assertThat(e.getMessage(), containsString("Usuario ya existe"));
        }

        // Verificar que NO se guardó nada
        verify(usuarioRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("✓ Obtener usuario por nombre debe retornar Optional<Usuario>")
    void should_get_usuario_by_nombre_successfully() {
        // Given
        String nombre = "juan.perez";
        Usuario usuarioEsperado = new Usuario(nombre, "hash123");

        when(usuarioRepositoryPort.findByNombre(nombre))
            .thenReturn(Optional.of(usuarioEsperado));

        // When
        Optional<Usuario> resultado = service.getByNombre(nombre);

        // Then
        assertThat(resultado, isPresentAndEqual(usuarioEsperado));
        verify(usuarioRepositoryPort, times(1)).findByNombre(nombre);
    }

    @Test
    @DisplayName("✓ Obtener usuario por nombre debe retornar empty si no existe")
    void should_return_empty_when_usuario_not_found_by_nombre() {
        // Given
        String nombre = "usuario_inexistente";
        when(usuarioRepositoryPort.findByNombre(nombre))
            .thenReturn(Optional.empty());

        // When
        Optional<Usuario> resultado = service.getByNombre(nombre);

        // Then
        assertThat(resultado, isEmpty());
        verify(usuarioRepositoryPort, times(1)).findByNombre(nombre);
    }

    @Test
    @DisplayName("✓ Obtener usuario por ID debe retornar Optional<Usuario>")
    void should_get_usuario_by_id_successfully() {
        // Given
        Long id = 1L;
        Usuario usuarioEsperado = new Usuario("juan", "hash123");

        when(usuarioRepositoryPort.findById(id))
            .thenReturn(Optional.of(usuarioEsperado));

        // When
        Optional<Usuario> resultado = service.getById(id);

        // Then
        assertThat(resultado, isPresentAndEqual(usuarioEsperado));
        verify(usuarioRepositoryPort, times(1)).findById(id);
    }

    // Hamcrest matcher custom para Optional
    private static <T> org.hamcrest.Matcher<Optional<T>> isPresentAndEqual(T expected) {
        return new org.hamcrest.TypeSafeMatcher<Optional<T>>() {
            @Override
            protected boolean matchesSafely(Optional<T> item) {
                return item.isPresent() && item.get().equals(expected);
            }

            @Override
            public void describeTo(org.hamcrest.Description description) {
                description.appendText("Optional containing ").appendValue(expected);
            }
        };
    }

    private static <T> org.hamcrest.Matcher<Optional<T>> isEmpty() {
        return new org.hamcrest.TypeSafeMatcher<Optional<T>>() {
            @Override
            protected boolean matchesSafely(Optional<T> item) {
                return item.isEmpty();
            }

            @Override
            public void describeTo(org.hamcrest.Description description) {
                description.appendText("Optional empty");
            }
        };
    }
}
