package com.bottrading.wallet.application.port.out;

import java.util.List;
import java.util.Optional;

import com.bottrading.wallet.domain.Wallet;

/**
 * Puerto de salida para persistencia de wallets.
 */
public interface WalletRepositoryPort {

    List<Wallet> findByUsuarioId(Long usuarioId);

    Optional<Wallet> findById(Long id);

    <S extends Wallet> S save(S wallet);
}
