package com.bottrading.infrastructure.validation;

import com.bottrading.domain.user.Usuario;
import com.bottrading.domain.wallet.Wallet;
import com.bottrading.domain.wallet.WalletType;
import com.bottrading.exceptions.ValidationException;
import com.bottrading.domain.wallet.WalletRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * Servicio encargado de la gestión de billeteras (Wallets).
 * Maneja la creación, eliminación, consulta de saldos y control de estado (Activo/Inactivo).
 * Cada usuario puede tener múltiples wallets, pero cada nombre debe ser único por usuario.
 */
@Service
public class WalletService {

    private final WalletRepository walletRepo;
    private final SessionManager sessionManager;

    @Autowired
    public WalletService(WalletRepository walletRepo, SessionManager sessionManager) {
        this.walletRepo = walletRepo;
        this.sessionManager = sessionManager;
    }

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
        Long usuarioId = usuario.getId();

        if (walletRepo.existsByUsuarioIdAndNombre(usuarioId, nombre)) {
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
                .orElseThrow(() -> new ValidationException("Wallet no encontrada"));

        if (wallet.isActive()) {
            throw new ValidationException("No se puede eliminar una wallet activa. Detén las estrategias asociadas primero.");
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
        Wallet wallet = walletRepo.findByUsuarioIdAndNombre(usuario.getId(), nombre)
                .orElseThrow(() -> new ValidationException("Wallet no encontrada: " + nombre));

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
        return walletRepo.findByUsuarioIdAndNombre(usuario.getId(), nombre)
                .map(Wallet::getBalanceReal)
                .orElseThrow(() -> new ValidationException("Wallet no encontrada: " + nombre));
    }

    /**
     * Genera un listado formateado de todas las wallets del usuario.
     * Incluye información de saldo real, disponible y estado.
     *
     * @return Lista de cadenas de texto listas para imprimir en consola.
     */
    public List<String> listarWallets() {
        Usuario usuario = sessionManager.getCurrentUser();

        return walletRepo.findByUsuarioIdOrderByNombre(usuario.getId()).stream()
                .map(w -> String.format("%s | Equity: %s | Disponible: %s | Tipo: %s %s",
                        w.getNombre(),
                        w.getBalanceReal().toPlainString(),
                        w.getBalanceDisponible().toPlainString(),
                        w.getType(),
                        w.isActive() ? "[EN USO]" : ""))
                .toList();
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

        return walletRepo.findByUsuarioIdAndNombre(usuario.getId(), nombreWallet)
                .map(Wallet::getId)
                .orElseThrow(() -> new ValidationException(
                        "No se encontró la wallet '" + nombreWallet + "' para el usuario actual"));
    }
}