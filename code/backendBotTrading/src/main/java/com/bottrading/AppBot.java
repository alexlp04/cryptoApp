package com.bottrading;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import com.bottrading.backtesting.application.port.in.ExecuteBacktestUseCase;
import com.bottrading.interfaces.cli.CliCommandContext;
import com.bottrading.interfaces.cli.commands.BacktestCommand;
import com.bottrading.interfaces.cli.commands.CbiCommand;
import com.bottrading.interfaces.cli.commands.CliCommand;
import com.bottrading.interfaces.cli.commands.CreatePaperWalletCommand;
import com.bottrading.interfaces.cli.commands.FetchCommand;
import com.bottrading.interfaces.cli.commands.FetchGapDetector;
import com.bottrading.interfaces.cli.commands.HelpCommand;
import com.bottrading.interfaces.cli.commands.ListActiveStrategiesCommand;
import com.bottrading.interfaces.cli.commands.ListStoppedStrategiesCommand;
import com.bottrading.interfaces.cli.commands.ListStrategiesCommand;
import com.bottrading.interfaces.cli.commands.ListStrategyFilesCommand;
import com.bottrading.interfaces.cli.commands.ListTerminatedStrategiesCommand;
import com.bottrading.interfaces.cli.commands.ListWalletsCommand;
import com.bottrading.interfaces.cli.commands.LoginCommand;
import com.bottrading.interfaces.cli.commands.LogoutCommand;
import com.bottrading.interfaces.cli.commands.ModelsCommand;
import com.bottrading.interfaces.cli.commands.OptimizeCommand;
import com.bottrading.interfaces.cli.commands.SignupCommand;
import com.bottrading.interfaces.cli.commands.StartCommand;
import com.bottrading.interfaces.cli.commands.StopCommand;
import com.bottrading.interfaces.cli.commands.TermCommand;
import com.bottrading.interfaces.cli.commands.TradeCommand;
import com.bottrading.interfaces.cli.commands.TrainCommand;
import com.bottrading.market.application.port.in.FetchMarketDataUseCase;
import com.bottrading.market.application.port.in.MarketDataUseCase;
import com.bottrading.shared.utils.ConsoleLoader;
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

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
/**
 * Shell CLI principal: registra comandos y enruta entradas del usuario.
 */
public class AppBot implements CommandLineRunner {

    private final StrategyLifecycleUseCase strategyLifecycleUseCase;
    private final QueryStrategiesUseCase queryStrategiesUseCase;
    private final StrategyCatalogUseCase strategyCatalogUseCase;
    private final ExecuteBacktestUseCase executeBacktestUseCase;
    private final MarketDataUseCase marketDataService;
    private final FetchMarketDataUseCase fetchMarketDataService;
    private final CreateUsuarioUseCase createUsuarioUseCase;
    private final GetUsuarioUseCase getUsuarioUseCase;
    private final AuthenticateUseCase authenticateUseCase;
    private final WalletManagementUseCase walletManagementUseCase;
    private final SessionManager sessionManager;
    private final TrainModelUseCase aiTrainingService;
    private final OptimizeModelUseCase aiOptimizationService;
    private final FetchGapDetector fetchGapDetector;
    private final ConfigurableApplicationContext applicationContext;

    private final Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8);
    private final Map<String, CliCommand> commandRegistry = new HashMap<>();
    private CliCommandContext commandContext;

    /**
     * Crea el contexto de comandos y registra el catalogo disponible.
     */
    @PostConstruct
    void initializeCommandRegistry() {
        this.commandContext = new CliCommandContext(
                scanner,
                createUsuarioUseCase,
                getUsuarioUseCase,
                authenticateUseCase,
                sessionManager,
                strategyLifecycleUseCase,
                queryStrategiesUseCase,
                strategyCatalogUseCase,
                executeBacktestUseCase,
                walletManagementUseCase,
                aiTrainingService,
                aiOptimizationService,
                marketDataService,
                fetchMarketDataService,
                fetchGapDetector,
                this::uiPrint,
                this::uiPrintln);

        registerCommand(new SignupCommand());
        registerCommand(new LoginCommand());
        registerCommand(new LogoutCommand());
        registerCommand(new HelpCommand());
        registerCommand(new TradeCommand());
        registerCommand(new TrainCommand());
        registerCommand(new OptimizeCommand());
        registerCommand(new FetchCommand());
        registerCommand(new BacktestCommand());
        registerCommand(new StartCommand());
        registerCommand(new StopCommand());
        registerCommand(new TermCommand());
        registerCommand(new CreatePaperWalletCommand());
        registerCommand(new ListWalletsCommand());
        registerCommand(new ListStrategyFilesCommand());
        registerCommand(new ListStrategiesCommand());
        registerCommand(new ListActiveStrategiesCommand());
        registerCommand(new ListStoppedStrategiesCommand());
        registerCommand(new ListTerminatedStrategiesCommand());
        registerCommand(new ModelsCommand());
        registerCommand(new CbiCommand());
    }

    private void registerCommand(CliCommand command) {
        commandRegistry.put(command.name(), command);
    }

    /**
     * Bucle interactivo principal de la consola.
     */
    @Override
    public void run(String... args) {
        mostrarBienvenida();

        boolean ejecutando = true;
        while (ejecutando) {
            uiPrint(buildPrompt());
            String linea = scanner.nextLine().trim();
            ejecutando = procesarLinea(linea);
        }
    }

    private String buildPrompt() {
        if (sessionManager.isLoggedIn()) {
            return "[" + sessionManager.getCurrentUser().getNombre() + "] > ";
        }
        return "[sin sesion] > ";
    }

    @SuppressWarnings("java:S106")
    public void uiPrint(String mensaje) {
        System.out.print(mensaje);
    }

    @SuppressWarnings("java:S106")
    public void uiPrintln(String mensaje) {
        System.out.println(mensaje);
    }

    /**
     * Muestra banner de bienvenida al iniciar la CLI.
     */
    private void mostrarBienvenida() {
        ConsoleLoader.getInstance().stopClear();
        uiPrintln("=================================================");
        uiPrintln("   BACKEND BOT TRADING - SPRING BOOT ENGINE      ");
        uiPrintln("=================================================");
        uiPrintln("Escribe 'ayuda' para ver los comandos.");
    }

    private boolean procesarLinea(String linea) {
        if (linea.isEmpty())
            return true;

        if (linea.equalsIgnoreCase("exit") || linea.equalsIgnoreCase("quit")) {
            ejecutarSalidaOrdenada();
            return false;
        }

        procesarComando(linea);
        return true;
    }

    /**
     * Resuelve y ejecuta un comando registrado.
     */
    private void procesarComando(String comando) {
        String[] parts = comando.split(" ");
        String cmd = parts[0].toLowerCase();

        try {
            CliCommand registeredCommand = commandRegistry.get(cmd);
            if (registeredCommand != null) {
                registeredCommand.execute(parts, commandContext);
                return;
            }

            uiPrintln("Comando desconocido. Escribe 'ayuda'.");
        } catch (Exception e) {
            log.error("Error ejecutando comando '{}': {}", cmd, e.getMessage());
            uiPrintln("Error: " + e.getMessage());
        }
    }

    /**
     * Ejecuta un apagado ordenado del contexto Spring y termina la JVM.
     */
    private void ejecutarSalidaOrdenada() {
        uiPrintln("Cerrando sistema...");
        if (sessionManager.isLoggedIn()) {
            sessionManager.logout();
        }
        log.info("Usuario solicito cierre (comando quit/exit).");
        uiPrintln("Bye!");
        int exitCode = SpringApplication.exit(applicationContext, () -> 0);
        System.exit(exitCode);
    }

}