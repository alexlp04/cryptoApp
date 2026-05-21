package com.bottrading.wallet.infrastructure.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.bottrading.wallet.application.port.out.WalletRepositoryPort;
import com.bottrading.wallet.domain.Wallet;
import com.bottrading.wallet.domain.WalletType;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class WalletPersistenceAdapter implements WalletRepositoryPort {

    private final WalletRepository walletRepository;

    @Override
    public List<Wallet> findByUsuarioIdOrderByNombre(Long usuarioId) {
        return walletRepository.findByUsuarioIdOrderByNombre(usuarioId);
    }

    @Override
    public List<Wallet> findByUsuarioIdAndTypeAndIsActiveFalseOrderByNombre(Long usuarioId, WalletType type) {
        return walletRepository.findByUsuarioIdAndTypeAndIsActiveFalseOrderByNombre(usuarioId, type);
    }

    @Override
    public Optional<Wallet> findByUsuarioIdAndNombre(Long usuarioId, String nombre) {
        return walletRepository.findByUsuarioIdAndNombre(usuarioId, nombre);
    }

    @Override
    public Optional<Wallet> findByIdWithLock(Long id) {
        return walletRepository.findByIdWithLock(id);
    }

    @Override
    public boolean existsByUsuarioIdAndNombre(Long usuarioId, String nombre) {
        return walletRepository.existsByUsuarioIdAndNombre(usuarioId, nombre);
    }

    @Override
    public Optional<Wallet> findById(Long id) {
        return walletRepository.findById(id);
    }

    @Override
    public Wallet save(Wallet wallet) {
        return walletRepository.save(wallet);
    }

    @Override
    public void delete(Wallet wallet) {
        walletRepository.delete(wallet);
    }
}
