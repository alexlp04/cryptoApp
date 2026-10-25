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

    private static final java.util.regex.Pattern NOMBRE_VALIDO = java.util.regex.Pattern.compile("^[A-Za-z0-9_.-]{3,32}$");

    private final UsuarioRepositoryPort usuarioRepositoryPort;

    @Override
    @Transactional
    public Usuario create(String nombre, String password) {
        log.info("Creando usuario con nombre: {}", nombre);

        if (nombre == null || !NOMBRE_VALIDO.matcher(nombre).matches()) {
            throw new ValidationException(
                    "El nombre de usuario debe tener 3-32 caracteres: letras, números, '_', '.' o '-'");
        }

        // Validar que el usuario no exista
        Optional<Usuario> existing = usuarioRepositoryPort.findByNombreAndEliminadoFalse(nombre);
        if (existing.isPresent()) {
            throw new ValidationException("El nombre de usuario ya esta en uso");
        }

        // Hashing de password en capa de aplicacion para no exponerlo en CLI.
        Usuario usuario = new Usuario(nombre, HashUtils.hashPassword(password));

        // Persistir y retornar. Dos altas simultáneas del mismo nombre pasan el check anterior y la
        // restricción única de la BBDD las frena: se traduce a un error de validación legible.
        try {
            return usuarioRepositoryPort.save(usuario);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new ValidationException("El nombre de usuario ya esta en uso", e);
        }
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
