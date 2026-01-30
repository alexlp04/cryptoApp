package com.bottrading.services;

import com.bottrading.beans.Usuario;
import com.bottrading.beans.Wallet;
import com.bottrading.beans.WalletType;
import com.bottrading.repositories.WalletRepository;
import com.bottrading.utils.SessionManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class WalletService {

    @Autowired
    private WalletRepository walletRepo;

    @Autowired
    private SessionManager sessionManager;

    /* ===================== CREAR / ELIMINAR ===================== */

    @Transactional
    public void crearWallet(String nombre, BigDecimal balanceInicial, boolean isReal) {
        Usuario usuario = sessionManager.getCurrentUser();

        if (walletRepo.existsByUsuarioAndNombre(usuario, nombre)) {
            throw new RuntimeException("Ya existe una wallet con el nombre: " + nombre);
        }

        // Validación: balance inicial no puede ser negativo
        if (balanceInicial.compareTo(BigDecimal.ZERO) < 0) {
            throw new RuntimeException("El balance inicial no puede ser negativo");
        }

        Wallet wallet = new Wallet();
        wallet.setNombre(nombre);
        wallet.setUsuario(usuario);
        wallet.setBalanceReal(balanceInicial);
        wallet.setBalanceDisponible(balanceInicial); // Al inicio, todo es disponible
        wallet.setType(isReal ? WalletType.REAL : WalletType.PAPER);
        wallet.setActive(false);

        walletRepo.save(wallet);
    }

    @Transactional
    public void eliminarWallet(Long id) {
        Wallet wallet = walletRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("Wallet no encontrada"));

        if (wallet.isActive()) {
            throw new RuntimeException("No se puede eliminar una wallet en uso por el bot");
        }

        walletRepo.delete(wallet);
    }

    /* ===================== ESTADO (TRADING) ===================== */

    @Transactional
    public void cambiarEstadoActivo(String nombre, boolean activo) {
        Usuario usuario = sessionManager.getCurrentUser();
        Wallet wallet = walletRepo.findByUsuarioAndNombre(usuario, nombre)
                .orElseThrow(() -> new RuntimeException("Wallet no encontrada"));

        wallet.setActive(activo);
        walletRepo.save(wallet);
    }

    /* ===================== CONSULTAS ===================== */

    public BigDecimal getBalance(String nombre) {
        Usuario usuario = sessionManager.getCurrentUser();
        return walletRepo.findByUsuarioAndNombre(usuario, nombre)
                .map(Wallet::getBalanceReal)
                .orElseThrow(() -> new RuntimeException("Wallet no encontrada"));
    }

    public List<String> listarWallets() {
        Usuario usuario = sessionManager.getCurrentUser();

        return walletRepo.findByUsuarioOrderByNombre(usuario).stream()
                .map(w -> String.format("%s | Real: %s | Disponible: %s | %s %s",
                        w.getNombre(),
                        w.getBalanceReal().toPlainString(),
                        w.getBalanceDisponible().toPlainString(),
                        w.getType(),
                        w.isActive() ? "[EN USO]" : ""))
                .collect(Collectors.toList());
    }

    public Long obtenerIdPorNombre(String nombreWallet) {
        Usuario usuario = sessionManager.getCurrentUser();

        return walletRepo.findByUsuarioAndNombre(usuario, nombreWallet)
                .map(Wallet::getId)
                .orElseThrow(() -> new RuntimeException(
                        "No se encontró la wallet '" + nombreWallet + "' para este usuario"));
    }
}