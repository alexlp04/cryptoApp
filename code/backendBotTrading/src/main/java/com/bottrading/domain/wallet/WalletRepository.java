package com.bottrading.domain.wallet;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;

@Repository
public interface WalletRepository extends JpaRepository<Wallet, Long> {

    // Sustituye findByIdWithLock
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM Wallet w WHERE w.id = :id")
    Optional<Wallet> findByIdWithLock(Long id);

    // Sustituye findByNombre
    Optional<Wallet> findByUsuarioIdAndNombre(Long usuarioId, String nombre);

    // Sustituye findByUsuario
    List<Wallet> findByUsuarioIdOrderByNombre(Long usuarioId);

    // Sustituye findAvailableByType
    List<Wallet> findByUsuarioIdAndTypeAndIsActiveFalseOrderByNombre(Long usuarioId, WalletType type);

    boolean existsByUsuarioIdAndNombre(Long usuarioId, String nombre);
}