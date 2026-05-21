package com.bottrading.user.domain;

import com.bottrading.shared.domain.BaseEntity;
import java.util.Objects;

/**
 * DOMAIN ENTITY — Usuario de dominio.
 * 
 * Esta clase es COMPLETAMENTE PURA:
 *  • Sin anotaciones JPA
 *  • Sin anotaciones Spring
 *  • Validaciones en constructor
 *  • Sin dependencias externas
 * 
 * La persistencia es responsabilidad de infrastructure/persistence/adapter/.
 */
public class Usuario extends BaseEntity {

    private final String nombre;
    private final String passwordHash;

    /**
     * Crea un usuario con validaciones.
     * 
     * @param nombre nombre del usuario (no nulo, no vacío)
     * @param passwordHash hash de contraseña (no nulo)
     * @throws IllegalArgumentException si los parámetros son inválidos
     */
    public Usuario(String nombre, String passwordHash) {
        super();
        if (nombre == null || nombre.trim().isEmpty()) {
            throw new IllegalArgumentException("Usuario.nombre no puede ser nulo ni vacío");
        }
        if (passwordHash == null || passwordHash.trim().isEmpty()) {
            throw new IllegalArgumentException("Usuario.passwordHash no puede ser nulo ni vacío");
        }
        this.nombre = Objects.requireNonNull(nombre);
        this.passwordHash = Objects.requireNonNull(passwordHash);
    }

    // Constructor protegido para deserialization (si es necesario)
    protected Usuario() {
        super();
        this.nombre = null;
        this.passwordHash = null;
    }

    public String getNombre() {
        return nombre;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    // COMPORTAMIENTO DE DOMINIO
    /**
     * Verifica si esta entidad tienen roles suficientes.
     * Puede expandirse con lógica de autorización más compleja.
     */
    public boolean esValido() {
        return nombre != null && !nombre.isEmpty() &&
               passwordHash != null && !passwordHash.isEmpty();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Usuario usuario = (Usuario) o;
        return Objects.equals(nombre, usuario.nombre) &&
               Objects.equals(passwordHash, usuario.passwordHash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(nombre, passwordHash);
    }

    @Override
    public String toString() {
        return "Usuario{" + "nombre='" + nombre + '\'' + '}';
    }
}
