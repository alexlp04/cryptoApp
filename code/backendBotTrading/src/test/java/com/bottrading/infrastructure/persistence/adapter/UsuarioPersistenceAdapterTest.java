package com.bottrading.infrastructure.persistence.adapter;

import com.bottrading.domain.user.Usuario;
import com.bottrading.infrastructure.persistence.jpa.UsuarioJpaRepository;
import com.bottrading.infrastructure.persistence.mapper.UsuarioMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import org.springframework.test.context.ActiveProfiles;

/**
 * TEST INFRAESTRUCTURA - UsuarioPersistenceAdapter
 * 
 * Características:
 *  • @DataJpaTest → Contexto JPA mínimo con H2 en memoria
 *  • @ActiveProfiles("test") → Usar application-test.yml
 *  • Testea: persistencia real, mappers, conversiones
 *  • NO testea comportamiento de negocio (eso va en UsuarioApplicationServiceTest)
 */
@DataJpaTest
@Import({UsuarioMapper.class, UsuarioPersistenceAdapter.class})
@ActiveProfiles("test")
@DisplayName("Infrastructure - UsuarioPersistenceAdapter")
class UsuarioPersistenceAdapterTest {

    @Autowired
    private UsuarioJpaRepository jpaRepository;

    @Autowired
    private UsuarioMapper mapper;

    @Autowired
    private UsuarioPersistenceAdapter adapter;

    @BeforeEach
    void setUp() {
        // El repositorio JPA se limpia automáticamente entre tests
        jpaRepository.deleteAll();
    }

    @Test
    @DisplayName("✓ Debe persistir usuario y recuperarlo por ID")
    void should_save_and_find_usuario_by_id() {
        // Given
        Usuario usuarioAlGuardar = new Usuario("juan.perez", "hash_seguro");

        // When
        Usuario usuarioGuardado = adapter.save(usuarioAlGuardar);
        Optional<Usuario> usuarioRecuperado = adapter.findById(usuarioGuardado.getId());

        // Then
        assertThat(usuarioRecuperado, notNullValue());
        assertThat(usuarioRecuperado.isPresent(), is(true));
        assertThat(usuarioRecuperado.get().getNombre(), is(equalTo("juan.perez")));
    }

    @Test
    @DisplayName("✓ Debe persistir usuario y recuperarlo por nombre")
    void should_save_and_find_usuario_by_nombre() {
        // Given
        Usuario usuarioAlGuardar = new Usuario("pedro.gomez", "hash123");

        // When
        adapter.save(usuarioAlGuardar);
        Optional<Usuario> usuarioRecuperado = adapter.findByNombre("pedro.gomez");

        // Then
        assertThat(usuarioRecuperado.isPresent(), is(true));
        assertThat(usuarioRecuperado.get().getNombre(), is(equalTo("pedro.gomez")));
        assertThat(usuarioRecuperado.get().getPasswordHash(), is(equalTo("hash123")));
    }

    @Test
    @DisplayName("✓ Debe lister todos los usuarios")
    void should_find_all_usuarios() {
        // Given
        Usuario usuario1 = new Usuario("usuario1", "hash1");
        Usuario usuario2 = new Usuario("usuario2", "hash2");

        adapter.save(usuario1);
        adapter.save(usuario2);

        // When
        var usuarios = adapter.findAll();

        // Then
        assertThat(usuarios, hasSize(greaterThanOrEqualTo(2)));
    }

    @Test
    @DisplayName("✓ Debe eliminar usuario por ID")
    void should_delete_usuario_by_id() {
        // Given
        Usuario usuario = adapter.save(new Usuario("usuario_a_eliminar", "hash"));
        Long id = usuario.getId();

        // When
        adapter.deleteById(id);
        Optional<Usuario> usuarioEliminado = adapter.findById(id);

        // Then
        assertThat(usuarioEliminado.isPresent(), is(false));
    }

    @Test
    @DisplayName("✓ Mapper debe convertir Domain → JPA → Domain correctamente")
    void should_map_usuario_domain_to_jpa_and_back() {
        // Given
        Usuario usuarioDomain = new Usuario("mapper_test", "hash_mapper");

        // When
        var jpaEntity = mapper.toJpa(usuarioDomain);
        jpaEntity.setId(123L); // Simular ID asignado por BD

        var usuarioDomainRecuperado = mapper.toDomain(jpaEntity);

        // Then
        assertThat(usuarioDomainRecuperado.getNombre(), is(equalTo("mapper_test")));
        assertThat(usuarioDomainRecuperado.getPasswordHash(), is(equalTo("hash_mapper")));
    }

    @Test
    @DisplayName("✓ Deve encontrar usuario por nombre (método personalizado JPA)")
    void should_find_usuario_by_nombre_using_jpa_method() {
        // Given
        var usuarioJpa = new com.bottrading.infrastructure.persistence.entity.UsuarioJpaEntity(
            "custom_usuario", "custom_hash"
        );
        jpaRepository.save(usuarioJpa);

        // When
        var resultado = jpaRepository.findByNombre("custom_usuario");

        // Then
        assertThat(resultado.isPresent(), is(true));
        assertThat(resultado.get().getNombre(), is(equalTo("custom_usuario")));
    }
}
