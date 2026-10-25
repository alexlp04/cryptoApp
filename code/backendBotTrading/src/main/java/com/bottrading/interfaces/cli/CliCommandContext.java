package com.bottrading.interfaces.cli;

import java.util.Scanner;
import java.util.function.Consumer;

import com.bottrading.backtesting.application.port.in.ExecuteBacktestUseCase;
import com.bottrading.interfaces.cli.commands.FetchGapDetector;
import com.bottrading.market.application.port.in.FetchMarketDataUseCase;
import com.bottrading.market.application.port.in.MarketDataUseCase;
import com.bottrading.strategy.application.port.in.QueryStrategiesUseCase;
import com.bottrading.strategy.application.port.in.StrategyCatalogUseCase;
import com.bottrading.strategy.application.port.in.StrategyLifecycleUseCase;
import com.bottrading.training.application.port.in.OptimizeModelUseCase;
import com.bottrading.training.application.port.in.TrainModelUseCase;
import com.bottrading.user.application.port.in.AuthenticateUseCase;
import com.bottrading.user.application.port.in.CreateUsuarioUseCase;
import com.bottrading.user.application.port.in.GetUsuarioUseCase;
import com.bottrading.user.infrastructure.SessionManager;
import com.bottrading.wallet.application.port.in.WalletManagementUseCase;

/**
 * Shared context for CLI commands.
 */
public record CliCommandContext(
        Scanner scanner,
        CreateUsuarioUseCase createUsuarioUseCase,
        GetUsuarioUseCase getUsuarioUseCase,
        AuthenticateUseCase authenticateUseCase,
        SessionManager sessionManager,
        StrategyLifecycleUseCase strategyLifecycleUseCase,
        QueryStrategiesUseCase queryStrategiesUseCase,
        StrategyCatalogUseCase strategyCatalogUseCase,
        ExecuteBacktestUseCase executeBacktestUseCase,
        WalletManagementUseCase walletManagementUseCase,
        TrainModelUseCase aiTrainingService,
        OptimizeModelUseCase aiOptimizationService,
        MarketDataUseCase marketDataService,
        FetchMarketDataUseCase fetchMarketDataService,
        FetchGapDetector fetchGapDetector,
        Consumer<String> print,
        Consumer<String> println) {

    /**
     * Devuelve una copia del contexto leyendo de otro Scanner.
     * Lo usa el comando 'test' para alimentar respuestas pregrabadas.
     */
    public CliCommandContext withScanner(Scanner nuevoScanner) {
        return new CliCommandContext(
                nuevoScanner, createUsuarioUseCase, getUsuarioUseCase, authenticateUseCase,
                sessionManager, strategyLifecycleUseCase, queryStrategiesUseCase, strategyCatalogUseCase,
                executeBacktestUseCase, walletManagementUseCase, aiTrainingService, aiOptimizationService,
                marketDataService, fetchMarketDataService, fetchGapDetector, print, println);
    }
}
