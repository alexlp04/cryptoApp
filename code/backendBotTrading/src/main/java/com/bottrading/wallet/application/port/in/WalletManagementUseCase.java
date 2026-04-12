package com.bottrading.wallet.application.port.in;

import java.math.BigDecimal;
import java.util.List;

/**
 * Puerto de entrada para gestión de wallets.
 */
public interface WalletManagementUseCase {

    void crearWallet(String nombre, BigDecimal saldoInicial, boolean isReal);

    List<String> listarWallets();

    BigDecimal getBalance(String nombre);

    Long obtenerIdPorNombre(String nombreWallet);
}
