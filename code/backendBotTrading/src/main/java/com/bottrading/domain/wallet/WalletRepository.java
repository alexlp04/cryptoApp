package com.bottrading.domain.wallet;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.bottrading.domain.user.Usuario;
import com.bottrading.domain.wallet.Wallet;
import com.bottrading.domain.wallet.WalletType;

import jakarta.persistence.LockModeType;

@Repository
public interface WalletRepository extends JpaRepository<Wallet, Long> {

    // Sustituye findByIdWithLock
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM Wallet w WHERE w.id = :id")
    Optional<Wallet> findByIdWithLock(Long id);

    // Sustituye findByNombre
    Optional<Wallet> findByUsuarioAndNombre(Usuario usuario, String nombre);

    // Sustituye findByUsuario
    List<Wallet> findByUsuarioOrderByNombre(Usuario usuario);

    // Sustituye findAvailableByType
    List<Wallet> findByUsuarioAndTypeAndIsActiveFalseOrderByNombre(Usuario usuario, WalletType type);

    boolean existsByUsuarioAndNombre(Usuario usuario, String nombre);
}