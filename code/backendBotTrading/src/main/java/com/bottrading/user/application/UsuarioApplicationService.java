package com.bottrading.user.application;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bottrading.shared.exceptions.ValidationException;
import com.bottrading.shared.utils.HashUtils;
import com.bottrading.user.application.port.in.AuthenticateUseCase;
import com.bottrading.user.application.port.in.CreateUsuarioUseCase;
import com.bottrading.user.application.port.in.GetUsuarioUseCase;
import com.bottrading.user.application.port.out.UsuarioRepositoryPort;
import com.bottrading.user.domain.Usuario;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * APPLICATION SERVICE - Casos de uso para Usuario.
 * 
 * Responsabilidades:
 *   1. Implementar los puertos de entrada (CreateUsuarioUseCase, GetUsuarioUseCase)
 *   2. Orquestar la lógica de aplicación
 *   3. Delegar operaciones de persistencia al puerto de salida
 * 
 * Flujo:
 *   Controller (interfaz)
 *              ↓
 *   CreateUsuarioUseCase (puerto de entrada)
 *              ↓
 *   UsuarioApplicationService (este servicio)
 *              ↓
 *   UsuarioRepositoryPort (puerto de salida)
 *              ↓
 *   UsuarioPersistenceAdapter (adapter)
 *              ↓
 *   Base de datos
 * 
 * IMPORTANTE: No contiene lógica de negocio compleja (eso va en domain/),
 * solo orquestación de componentes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UsuarioApplicationService implements CreateUsuarioUseCase, GetUsuarioUseCase, AuthenticateUseCase {

    private final UsuarioRepositoryPort usuarioRepositoryPort;

    @Override
    @Transactional
    public Usuario create(String nombre, String password) {
        log.info("Creando usuario con nombre: {}", nombre);

        // Validar que el usuario no exista
        Optional<Usuario> existing = usuarioRepositoryPort.findByNombreAndEliminadoFalse(nombre);
        if (existing.isPresent()) {
            throw new ValidationException("El nombre de usuario ya esta en uso");
        }

        // Hashing de password en capa de aplicacion para no exponerlo en CLI.
        Usuario usuario = new Usuario(nombre, HashUtils.hashPassword(password));

        // Persistir y retornar
        return usuarioRepositoryPort.save(usuario);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Usuario> getByNombre(String nombre) {
        return usuarioRepositoryPort.findByNombreAndEliminadoFalse(nombre);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Usuario> getById(Long id) {
        return usuarioRepositoryPort.findByIdAndEliminadoFalse(id);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean validarCredenciales(String nombre, String password) {
        return usuarioRepositoryPort.findByNombreAndEliminadoFalse(nombre)
                .map(usuario -> HashUtils.verificarPassword(password, usuario.getPasswordHash()))
                .orElse(false);
    }
}
