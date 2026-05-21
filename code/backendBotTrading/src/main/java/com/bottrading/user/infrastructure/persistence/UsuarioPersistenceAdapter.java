package com.bottrading.user.infrastructure.persistence;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.bottrading.user.application.port.out.UsuarioRepositoryPort;
import com.bottrading.user.domain.Usuario;

import lombok.RequiredArgsConstructor;

/**
 * ADAPTER O PUERTO DE SALIDA — Implementación de persistencia para Usuario.
 * 
 * Responsabilidades:
 *   1. Implementar UsuarioRepositoryPort (contrato de aplicación)
 *   2. Traducir Domain Entity → JPA Entity (con mapper)
 *   3. Delegar a UsuarioJpaRepository (Spring Data JPA)
 *   4. Aplicar lógica de transformación (domain ← jpa)
 * 
 * Flujo:
 *   Application Service (solicita usuarios del dominio)
 *                  ↓
 *          UsuarioRepositoryPort (interfaz)
 *                  ↓
 *   UsuarioPersistenceAdapter (este adapter)
 *                  ↓
 *          UsuarioJpaRepository (Spring Data JPA)
 *                  ↓
 *            Base de datos
 */
@Component
@RequiredArgsConstructor
public class UsuarioPersistenceAdapter implements UsuarioRepositoryPort {

    private final UsuarioJpaRepository jpaRepository;
    private final UsuarioMapper mapper;

    @Override
    public Optional<Usuario> findById(Long id) {
        return jpaRepository.findById(Objects.requireNonNull(id, "id no puede ser null"))
            .map(mapper::toDomain);
    }

    @Override
    public Optional<Usuario> findByNombre(String nombre) {
        return jpaRepository.findByNombre(nombre)
            .map(mapper::toDomain);
    }

    @Override
    public Optional<Usuario> findByIdAndEliminadoFalse(Long id) {
        return jpaRepository.findByIdAndEliminadoFalse(Objects.requireNonNull(id, "id no puede ser null"))
            .map(mapper::toDomain);
    }

    @Override
    public Optional<Usuario> findByNombreAndEliminadoFalse(String nombre) {
        return jpaRepository.findByNombreAndEliminadoFalse(nombre)
            .map(mapper::toDomain);
    }

    @Override
    public Usuario save(Usuario usuario) {
        var jpaEntity = mapper.toJpa(usuario);
        var savedJpaEntity = jpaRepository.save(Objects.requireNonNull(jpaEntity, "UsuarioJpaEntity no puede ser null"));
        return mapper.toDomain(savedJpaEntity);
    }
 
    @Override
    public void deleteById(Long id) {
        jpaRepository.deleteById(Objects.requireNonNull(id, "id no puede ser null"));
    }

    @Override
    public List<Usuario> findAll() {
        return jpaRepository.findAll()
            .stream()
            .map(mapper::toDomain)
            .toList();
    }
}
