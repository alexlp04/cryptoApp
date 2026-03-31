package com.bottrading.infrastructure.persistence.adapter;

import com.bottrading.application.user.port.out.UsuarioRepositoryPort;
import com.bottrading.domain.user.Usuario;
import com.bottrading.infrastructure.persistence.jpa.UsuarioJpaRepository;
import com.bottrading.infrastructure.persistence.mapper.UsuarioMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

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
        return jpaRepository.findById(id)
            .map(mapper::toDomain);
    }

    @Override
    public Optional<Usuario> findByNombre(String nombre) {
        return jpaRepository.findByNombre(nombre)
            .map(mapper::toDomain);
    }

    @Override
    public Usuario save(Usuario usuario) {
        var jpaEntity = mapper.toJpa(usuario);
        var savedJpaEntity = jpaRepository.save(jpaEntity);
        return mapper.toDomain(savedJpaEntity);
    }

    @Override
    public void deleteById(Long id) {
        jpaRepository.deleteById(id);
    }

    @Override
    public List<Usuario> findAll() {
        return jpaRepository.findAll()
            .stream()
            .map(mapper::toDomain)
            .collect(Collectors.toList());
    }
}
