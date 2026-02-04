package com.bottrading.services;

import com.bottrading.beans.Usuario;
import com.bottrading.beans.Wallet;
import com.bottrading.beans.WalletType;
import com.bottrading.repositories.WalletRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Servicio encargado de la gestión de billeteras (Wallets).
 * Maneja la creación, eliminación, consulta de saldos y control de estado (Activo/Inactivo).
 * Cada usuario puede tener múltiples wallets, pero cada nombre debe ser único por usuario.
 */
@Service
public class WalletService {

    @Autowired
    private WalletRepository walletRepo;

    @Autowired
    private SessionManager sessionManager;

    /**
     * Crea una nueva wallet asociada al usuario actualmente logueado.
     * Valida que el nombre no exista previamente y que el saldo inicial sea positivo.
     *
     * @param nombre         Nombre identificador de la wallet.
     * @param balanceInicial Saldo inicial (capital base).
     * @param isReal         Define si es una wallet de dinero real o simulado (Paper).
     * @throws RuntimeException Si el nombre ya existe o el balance es negativo.
     */
    @Transactional
    public void crearWallet(String nombre, BigDecimal balanceInicial, boolean isReal) {
        Usuario usuario = sessionManager.getCurrentUser();

        if (walletRepo.existsByUsuarioAndNombre(usuario, nombre)) {
            throw new RuntimeException("Ya existe una wallet con el nombre: " + nombre);
        }

        if (balanceInicial.compareTo(BigDecimal.ZERO) < 0) {
            throw new RuntimeException("El balance inicial no puede ser negativo");
        }

        Wallet wallet = new Wallet();
        wallet.setNombre(nombre);
        wallet.setUsuario(usuario);
        wallet.setBalanceReal(balanceInicial);
        wallet.setBalanceDisponible(balanceInicial); // Inicialmente, todo el capital está libre
        wallet.setType(isReal ? WalletType.REAL : WalletType.PAPER);
        wallet.setActive(false);

        walletRepo.save(wallet);
    }

    /**
     * Elimina una wallet del sistema.
     * No permite eliminar wallets que estén marcadas como activas (en uso por bots).
     *
     * @param id Identificador de la wallet a eliminar.
     */
    @Transactional
    public void eliminarWallet(long id) {
        Wallet wallet = walletRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("Wallet no encontrada"));

        if (wallet.isActive()) {
            throw new RuntimeException("No se puede eliminar una wallet activa. Detén las estrategias asociadas primero.");
        }

        walletRepo.delete(wallet);
    }

    /**
     * Actualiza el estado de actividad de una wallet.
     * Se utiliza para bloquear/desbloquear la wallet cuando se asigna a una estrategia.
     *
     * @param nombre Nombre de la wallet.
     * @param activo Nuevo estado (true = en uso, false = libre).
     */
    @Transactional
    public void cambiarEstadoActivo(String nombre, boolean activo) {
        Usuario usuario = sessionManager.getCurrentUser();
        Wallet wallet = walletRepo.findByUsuarioAndNombre(usuario, nombre)
                .orElseThrow(() -> new RuntimeException("Wallet no encontrada: " + nombre));

        wallet.setActive(activo);
        walletRepo.save(wallet);
    }

    /**
     * Obtiene el balance total (Real) de una wallet específica.
     *
     * @param nombre Nombre de la wallet.
     * @return El balance real (Equity total).
     */
    public BigDecimal getBalance(String nombre) {
        Usuario usuario = sessionManager.getCurrentUser();
        return walletRepo.findByUsuarioAndNombre(usuario, nombre)
                .map(Wallet::getBalanceReal)
                .orElseThrow(() -> new RuntimeException("Wallet no encontrada: " + nombre));
    }

    /**
     * Genera un listado formateado de todas las wallets del usuario.
     * Incluye información de saldo real, disponible y estado.
     *
     * @return Lista de cadenas de texto listas para imprimir en consola.
     */
    public List<String> listarWallets() {
        Usuario usuario = sessionManager.getCurrentUser();

        return walletRepo.findByUsuarioOrderByNombre(usuario).stream()
                .map(w -> String.format("%s | Equity: %s | Disponible: %s | Tipo: %s %s",
                        w.getNombre(),
                        w.getBalanceReal().toPlainString(),
                        w.getBalanceDisponible().toPlainString(),
                        w.getType(),
                        w.isActive() ? "[EN USO]" : ""))
                .collect(Collectors.toList());
    }

    /**
     * Recupera el ID de base de datos de una wallet a partir de su nombre.
     * Útil para enlazar estrategias con wallets.
     *
     * @param nombreWallet Nombre de la wallet.
     * @return El ID (Primary Key) de la wallet.
     */
    public Long obtenerIdPorNombre(String nombreWallet) {
        Usuario usuario = sessionManager.getCurrentUser();

        return walletRepo.findByUsuarioAndNombre(usuario, nombreWallet)
                .map(Wallet::getId)
                .orElseThrow(() -> new RuntimeException(
                        "No se encontró la wallet '" + nombreWallet + "' para el usuario actual"));
    }
}