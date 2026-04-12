package com.bottrading.wallet.infrastructure.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.bottrading.wallet.domain.Wallet;
import com.bottrading.wallet.domain.WalletType;

import jakarta.persistence.LockModeType;

@Repository
public interface WalletRepository extends JpaRepository<Wallet, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM Wallet w WHERE w.id = :id")
    Optional<Wallet> findByIdWithLock(Long id);

    Optional<Wallet> findByUsuarioIdAndNombre(Long usuarioId, String nombre);

    List<Wallet> findByUsuarioIdOrderByNombre(Long usuarioId);

    List<Wallet> findByUsuarioIdAndTypeAndIsActiveFalseOrderByNombre(Long usuarioId, WalletType type);

    boolean existsByUsuarioIdAndNombre(Long usuarioId, String nombre);
}