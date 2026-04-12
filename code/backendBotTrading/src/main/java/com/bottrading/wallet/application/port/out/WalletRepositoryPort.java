package com.bottrading.wallet.application.port.out;

import java.util.List;
import java.util.Optional;

import com.bottrading.wallet.domain.Wallet;

/**
 * Puerto de salida para persistencia de wallets.
 */
public interface WalletRepositoryPort {

    List<Wallet> findByUsuarioIdOrderByNombre(Long usuarioId);

    List<Wallet> findByUsuarioIdAndTypeAndIsActiveFalseOrderByNombre(Long usuarioId, com.bottrading.wallet.domain.WalletType type);

    Optional<Wallet> findByUsuarioIdAndNombre(Long usuarioId, String nombre);

    Optional<Wallet> findByIdWithLock(Long id);

    boolean existsByUsuarioIdAndNombre(Long usuarioId, String nombre);

    Optional<Wallet> findById(Long id);

    Wallet save(Wallet wallet);

    void delete(Wallet wallet);
}
