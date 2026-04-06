package com.bottrading.interfaces.cli;

import java.util.Scanner;
import java.util.function.Consumer;

import com.bottrading.market.application.MarketDataService;
import com.bottrading.strategy.application.EstrategiaService;
import com.bottrading.training.application.AIOptimizationService;
import com.bottrading.training.application.AITrainingService;
import com.bottrading.user.infrastructure.SessionManager;
import com.bottrading.user.infrastructure.UsuarioService;
import com.bottrading.wallet.infrastructure.WalletService;
import com.bottrading.interfaces.cli.commands.FetchGapDetector;

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
        AIOptimizationService aiOptimizationService,
        MarketDataService marketDataService,
        FetchGapDetector fetchGapDetector,
        Consumer<String> print,
        Consumer<String> println) {
}
