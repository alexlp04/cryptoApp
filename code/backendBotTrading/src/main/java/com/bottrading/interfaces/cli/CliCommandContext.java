package com.bottrading.interfaces.cli;

import com.bottrading.infrastructure.validation.SessionManager;
import com.bottrading.infrastructure.validation.UsuarioService;
import com.bottrading.application.strategy.EstrategiaService;
import com.bottrading.infrastructure.validation.WalletService;
import com.bottrading.application.training.AITrainingService;
import com.bottrading.application.market.MarketDataService;
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
