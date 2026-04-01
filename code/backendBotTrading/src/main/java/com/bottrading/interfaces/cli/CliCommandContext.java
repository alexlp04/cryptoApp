package com.bottrading.interfaces.cli;

import java.util.Scanner;
import java.util.function.Consumer;

import com.bottrading.application.market.MarketDataService;
import com.bottrading.application.strategy.EstrategiaService;
import com.bottrading.application.training.AITrainingService;
import com.bottrading.infrastructure.validation.SessionManager;
import com.bottrading.infrastructure.validation.UsuarioService;
import com.bottrading.infrastructure.validation.WalletService;
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
        MarketDataService marketDataService,
        FetchGapDetector fetchGapDetector,
        Consumer<String> print,
        Consumer<String> println) {
}
