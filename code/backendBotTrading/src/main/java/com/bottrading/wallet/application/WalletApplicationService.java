package com.bottrading.wallet.application;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bottrading.shared.exceptions.ValidationException;
import com.bottrading.user.domain.Usuario;
import com.bottrading.user.infrastructure.SessionManager;
import com.bottrading.wallet.application.port.in.WalletManagementUseCase;
import com.bottrading.wallet.application.port.out.WalletRepositoryPort;
import com.bottrading.wallet.domain.Wallet;
import com.bottrading.wallet.domain.WalletType;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class WalletApplicationService implements WalletManagementUseCase {

    private final WalletRepositoryPort walletRepositoryPort;
    private final SessionManager sessionManager;

    @Override
    @Transactional
    public void crearWallet(String nombre, BigDecimal balanceInicial, boolean isReal) {
        Usuario usuario = sessionManager.getCurrentUser();
        Long usuarioId = usuario.getId();

        if (walletRepositoryPort.existsByUsuarioIdAndNombre(usuarioId, nombre)) {
            throw new ValidationException("Ya existe una wallet con el nombre: " + nombre);
        }

        if (balanceInicial.compareTo(BigDecimal.ZERO) < 0) {
            throw new ValidationException("El balance inicial no puede ser negativo");
        }

        Wallet wallet = new Wallet();
        wallet.setNombre(nombre);
        wallet.setUsuarioId(usuarioId);
        wallet.setBalanceReal(balanceInicial);
        wallet.setBalanceDisponible(balanceInicial);
        wallet.setType(isReal ? WalletType.REAL : WalletType.PAPER);
        wallet.setActive(false);

        walletRepositoryPort.save(wallet);
    }

    @Transactional
    public void eliminarWallet(long id) {
        Wallet wallet = walletRepositoryPort.findById(id)
                .orElseThrow(() -> new ValidationException("Wallet no encontrada"));

        if (wallet.isActive()) {
            throw new ValidationException("No se puede eliminar una wallet activa. Deten las estrategias asociadas primero.");
        }

        walletRepositoryPort.delete(wallet);
    }

    @Transactional
    public void cambiarEstadoActivo(String nombre, boolean activo) {
        Usuario usuario = sessionManager.getCurrentUser();
        Wallet wallet = walletRepositoryPort.findByUsuarioIdAndNombre(usuario.getId(), nombre)
                .orElseThrow(() -> new ValidationException("Wallet no encontrada: " + nombre));

        wallet.setActive(activo);
        walletRepositoryPort.save(wallet);
    }

    @Override
    public BigDecimal getBalance(String nombre) {
        Usuario usuario = sessionManager.getCurrentUser();
        return walletRepositoryPort.findByUsuarioIdAndNombre(usuario.getId(), nombre)
                .map(Wallet::getBalanceReal)
                .orElseThrow(() -> new ValidationException("Wallet no encontrada: " + nombre));
    }

    @Override
    public List<String> listarWallets() {
        Usuario usuario = sessionManager.getCurrentUser();

        return walletRepositoryPort.findByUsuarioIdOrderByNombre(usuario.getId()).stream()
                .map(w -> String.format("%s | Equity: %s | Disponible: %s | Tipo: %s %s",
                        w.getNombre(),
                        w.getBalanceReal().toPlainString(),
                        w.getBalanceDisponible().toPlainString(),
                        w.getType(),
                        w.isActive() ? "[EN USO]" : ""))
                .toList();
    }

    @Override
    public Long obtenerIdPorNombre(String nombreWallet) {
        Usuario usuario = sessionManager.getCurrentUser();

        return walletRepositoryPort.findByUsuarioIdAndNombre(usuario.getId(), nombreWallet)
                .map(Wallet::getId)
                .orElseThrow(() -> new ValidationException(
                        "No se encontro la wallet '" + nombreWallet + "' para el usuario actual"));
    }
}
