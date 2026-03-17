package com.bottrading.interfaces.cli;

import com.bottrading.services.SessionManager;
import com.bottrading.services.UsuarioService;
import com.bottrading.services.EstrategiaService;
import com.bottrading.services.WalletService;
import com.bottrading.services.AITrainingService;
import com.bottrading.services.MarketDataService;
import java.util.Scanner;
import java.util.function.Consumer;

/**
 * Shared context for CLI commands.
 */
public record CliCommandContext(
        Scanner scanner,
        UsuarioService usuarioService,
        SessionManager sessionManager,
        EstrategiaService estrategiaService,
        WalletService walletService,
        AITrainingService aiTrainingService,
        MarketDataService marketDataService,
        Consumer<String> print,
        Consumer<String> println) {
}
