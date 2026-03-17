package com.bottrading;

import com.bottrading.services.*;
import com.bottrading.interfaces.cli.CliCommandContext;
import com.bottrading.interfaces.cli.commands.CliCommand;
import com.bottrading.interfaces.cli.commands.CbiCommand;
import com.bottrading.interfaces.cli.commands.CreatePaperWalletCommand;
import com.bottrading.interfaces.cli.commands.FetchCommand;
import com.bottrading.interfaces.cli.commands.HelpCommand;
import com.bottrading.interfaces.cli.commands.LoginCommand;
import com.bottrading.interfaces.cli.commands.LogoutCommand;
import com.bottrading.interfaces.cli.commands.BacktestCommand;
import com.bottrading.interfaces.cli.commands.ListActiveStrategiesCommand;
import com.bottrading.interfaces.cli.commands.ListStrategiesCommand;
import com.bottrading.interfaces.cli.commands.ListStoppedStrategiesCommand;
import com.bottrading.interfaces.cli.commands.ListStrategyFilesCommand;
import com.bottrading.interfaces.cli.commands.ListTerminatedStrategiesCommand;
import com.bottrading.interfaces.cli.commands.ListWalletsCommand;
import com.bottrading.interfaces.cli.commands.ModelsCommand;
import com.bottrading.interfaces.cli.commands.SignupCommand;
import com.bottrading.interfaces.cli.commands.StartCommand;
import com.bottrading.interfaces.cli.commands.StopCommand;
import com.bottrading.interfaces.cli.commands.TermCommand;
import com.bottrading.interfaces.cli.commands.TradeCommand;
import com.bottrading.interfaces.cli.commands.TrainCommand;
import com.bottrading.utils.ConsoleLoader;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class AppBot implements CommandLineRunner {

    private final EstrategiaService estrategiaService;
    private final MarketDataService marketDataService;
    private final UsuarioService usuarioService;
    private final WalletService walletService;
    private final SessionManager sessionManager;
    private final AITrainingService aiTrainingService;

    private final Scanner scanner = new Scanner(System.in);
    private final Map<String, CliCommand> commandRegistry = new HashMap<>();
    private CliCommandContext commandContext;

    @PostConstruct
    void initializeCommandRegistry() {
        this.commandContext = new CliCommandContext(
                scanner,
                usuarioService,
                sessionManager,
                estrategiaService,
                walletService,
                aiTrainingService,
                marketDataService,
                this::uiPrint,
                this::uiPrintln);

        registerCommand(new SignupCommand());
        registerCommand(new LoginCommand());
        registerCommand(new LogoutCommand());
        registerCommand(new HelpCommand());
        registerCommand(new TradeCommand());
        registerCommand(new TrainCommand());
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

    @Override
    public void run(String... args) {
        mostrarBienvenida();

        boolean ejecutando = true;
        while (ejecutando) {
            uiPrint("> ");
            String linea = scanner.nextLine().trim();
            ejecutando = procesarLinea(linea);
        }
    }

    @SuppressWarnings("java:S106")
    public void uiPrint(String mensaje) {
        System.out.print(mensaje);
    }

    @SuppressWarnings("java:S106")
    public void uiPrintln(String mensaje) {
        System.out.println(mensaje);
    }

    // =========================================================================
    // LÓGICA DE CONTROL
    // =========================================================================

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

    private void procesarComando(String comando) {
        String[] parts = comando.split(" ");
        String cmd = parts[0].toLowerCase();

        try {
            CliCommand registeredCommand = commandRegistry.get(cmd);
            if (registeredCommand != null) {
                registeredCommand.execute(parts, commandContext);
                return;
            }

            switch (cmd) {
                default -> uiPrintln("Comando desconocido. Escribe 'ayuda'.");
            }
        } catch (Exception e) {
            log.error("Error ejecutando comando '{}': {}", cmd, e.getMessage());
            uiPrintln("Error: " + e.getMessage());
        }
    }

    private void ejecutarSalidaOrdenada() {
        uiPrintln("Cerrando sistema...");
        if (sessionManager.isLoggedIn()) {
            sessionManager.logout();
        }
        log.info("Usuario solicito cierre (comando quit/exit).");
        uiPrintln("Bye!");
        // Spring Boot cerrará los contextos tras esto
    }

}