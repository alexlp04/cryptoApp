package com.bottrading;

import com.bottrading.services.*;
import com.bottrading.utils.CommandParser;
import com.bottrading.utils.ConsoleLoader;
import com.bottrading.utils.PathConfig;
import com.bottrading.beans.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
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
        ConsoleLoader.getInstance().stop();
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
            switch (cmd) {
                // Gestión Usuarios
                case "signup" -> flujoSignup();
                case "login" -> flujoLogin();
                case "logout" -> flujoLogout();

                // Wallets
                case "mkpwallet" -> crearWallet(parts);
                case "lw" -> listarWallets();

                // Consultas
                case "le" -> listarFicherosDeEstrategias();
                case "ls" -> listarEstrategias();
                case "lsa" -> listarEstrategiasActivas();
                case "lsd" -> listarEstrategiasDetenidas();
                case "lst" -> listarEstrategiasTerminadas();

                // Operativa
                case "fetch" -> ejecutarFetch(parts);
                case "backtest" -> ejecutarBacktest(parts);
                case "trade" -> ejecutarTrade(parts);
                case "train" -> ejecutarTrain(parts);
                case "start" -> ejecutarStart(parts);
                case "stop" -> ejecutarStop(parts);
                case "term" -> ejecutarTerm(parts);

                // Sistema
                case "cbi" -> conseguirDatos(parts);
                case "ayuda" -> mostrarAyuda();
                default -> uiPrintln("Comando desconocido. Escribe 'ayuda'.");
            }
        } catch (Exception e) {
            log.error("Error ejecutando comando '{}': {}", cmd, e.getMessage());
            uiPrintln("Error: " + e.getMessage());
        }
    }

    // =========================================================================
    // MÉTODOS DELEGADOS (Lógica específica)
    // =========================================================================

    private void flujoLogin() {
        uiPrint("Usuario: ");
        String nombre = scanner.nextLine().trim();
        uiPrint("Password: ");
        String pass = scanner.nextLine().trim();

        if (usuarioService.validarCredenciales(nombre, pass)) {
            Usuario u = usuarioService.obtenerPorNombre(nombre);
            sessionManager.login(u);
            uiPrintln("Sesion iniciada como " + u.getNombre());
        } else {
            uiPrintln("Credenciales incorrectas.");
        }
    }

    private void flujoSignup() {
        uiPrint("Nuevo Usuario: ");
        String nombre = scanner.nextLine().trim();
        uiPrint("Password: ");
        String pass = scanner.nextLine().trim();

        if (usuarioService.registrar(nombre, pass) == null) {
            uiPrintln("No se pudo registrar (quizas el usuario ya existe).");
        } else {
            uiPrintln("Registro exitoso.");
        }
    }

    private void flujoLogout() {
        if (sessionManager.isLoggedIn()) {
            sessionManager.logout();
            uiPrintln("Sesion cerrada correctamente.");
        } else {
            uiPrintln("No hay ninguna sesion iniciada.");
        }
    }

    private void crearWallet(String[] parts) {
        if (parts.length < 2)
            throw new IllegalArgumentException("Uso: mkpwallet <nombre>");
        if (!validarLogin()) {
            return;
        }
        walletService.crearWallet(parts[1], new BigDecimal("10000.00"), false);
        uiPrintln("Wallet de papel '" + parts[1] + "' creada con 10,000 USD.");
    }

    private void listarWallets() {
        if (!validarLogin()) {
            return;
        }
        uiPrintln("--- Tus Billeteras ---");
        // Asumiendo que Wallet tiene un toString decente, si no, usa getters
        walletService.listarWallets().forEach(this::uiPrintln);
    }

    private void listarFicherosDeEstrategias() {
        uiPrintln("--- Ficheros de Estrategias (.py) ---");
        estrategiaService.listarFicherosDeEstrategias().forEach(this::uiPrintln);
    }

    private void listarEstrategias() {
        if (!validarLogin()) {
            return;
        }
        uiPrintln("--- Estrategias en Ejecucion ---");
        estrategiaService.listarEstrategias().forEach(this::uiPrintln);
    }

    private void listarEstrategiasActivas() {
        if (!validarLogin()) {
            return;
        }
        uiPrintln("--- Estrategias en Ejecucion ---");
        estrategiaService.listarEstrategiasActivas().forEach(this::uiPrintln);
    }

    private void listarEstrategiasDetenidas() {
        if (!validarLogin()) {
            return;
        }
        uiPrintln("--- Historial Detenidas ---");
        estrategiaService.listarEstrategiasDetenidas().forEach(this::uiPrintln);
    }

    private void listarEstrategiasTerminadas() {
        if (!validarLogin()) {
            return;
        }
        uiPrintln("--- Historial Terminadas ---");
        estrategiaService.listarEstrategiasTerminadas().forEach(this::uiPrintln);
    }

    private void ejecutarFetch(String[] parts) {
        CommandParser args = new CommandParser(parts);

        if (args.hasErrorSintaxis()) {
            uiPrintln(args.getMensajeError());
            return;
        }

        if (args.getTimeframe() == null || args.getCoins().isEmpty()) {
            uiPrintln("Uso: fetch -tf <timeframe> -coins <coin1> [coin2...]");
            return;
        }

        uiPrintln("Descargando datos... (Esto puede tardar)");
        marketDataService.actualizarDatosMercado(args.getCoins(), args.getTimeframe());
        uiPrintln("Sincronizacion completa.");
    }

    private void ejecutarBacktest(String[] parts) throws Exception {
        CommandParser args = new CommandParser(parts);

        if (args.hasErrorSintaxis()) {
            uiPrintln(args.getMensajeError());
            return;
        }

        // Requiere estrategia, timeframe y monedas
        if (args.getEstrategia() == null || args.getTimeframe() == null || args.getCoins().isEmpty()) {
            uiPrintln("Uso: backtest -strategy <nombre> -tf <timeframe> -coins <coin1> [coin2...]");
            return;
        }

        uiPrintln("Iniciando Backtest...");
        estrategiaService.ejecutarBacktest(args.getEstrategia(), args.getTimeframe(), args.getCoins());
        uiPrintln("Backtest finalizado. Resultados guardados en CSV.");
    }

    private void ejecutarTrade(String[] parts) throws Exception {
        if (!validarLogin()) {
            return;
        }

        CommandParser args = new CommandParser(parts);

        if (args.hasErrorSintaxis()) {
            uiPrintln(args.getMensajeError());
            return;
        }

        // Validaciones específicas de trade
        if ((!args.isReal() && !args.isVirtual()) || (args.isReal() && args.isVirtual()) ||
                args.getTimeframe() == null || args.getCoins().isEmpty() ||
                (args.getEstrategia() == null && args.getModelo() == null)) {

            uiPrintln(
                    "Uso correcto: trade -v|-r [-strategy <nombre>] [-model <nombre>] -tf <timeframe> -coins <coin1> [coin2...]");
            return;
        }

        if (args.isReal()) {
            uiPrintln("ADVERTENCIA: Has seleccionado MODO REAL. Asegurate de tener fondos y entender los riesgos.");
        }

        // Validación de ficheros
        if (args.getEstrategia() != null && !PathConfig.existeEstrategia(args.getEstrategia())) {
            uiPrintln("Error: No se encuentra el script de estrategia '" + args.getEstrategia() + ".py'.");
            return;
        }

        if (args.getModelo() != null) {
            for (String coin : args.getCoins()) {
                if (!PathConfig.existeModelo(args.getModelo())) {
                    uiPrintln("Error: No se encuentra el modelo '" + args.getModelo() + ".");
                    uiPrintln("Pista: Ejecuta primero -> train -model " + args.getModelo() + " -tf "
                            + args.getTimeframe() + " -coins " + coin);
                    return;
                }
            }
        }

        // (Resto de la lógica de wallet y riesgo igual que antes...)
        uiPrintln("\nSelecciona una wallet:");
        walletService.listarWallets().forEach(this::uiPrintln);

        uiPrint("Nombre exacto de la wallet: ");
        String wName = scanner.nextLine().trim();

        BigDecimal balanceTotal = walletService.getBalance(wName);
        Long walletID = walletService.obtenerIdPorNombre(wName);
        BigDecimal comprometido = estrategiaService.getCapitalComprometido(walletID);
        BigDecimal disponible = balanceTotal.subtract(comprometido);

        uiPrintln(String.format("Saldo Total: %s | En Silos: %s | DISPONIBLE: %s", balanceTotal, comprometido,
                disponible));

        uiPrint("Capital a asignar a este bot: ");
        BigDecimal capitalAsignado = new BigDecimal(scanner.nextLine().trim());

        if (capitalAsignado.compareTo(disponible) > 0) {
            throw new IllegalArgumentException("Saldo insuficiente en la wallet.");
        }

        uiPrint("Riesgo por trade (0.01 - 1.0): ");
        BigDecimal risk = new BigDecimal(scanner.nextLine().trim());

        String identificador = (args.getEstrategia() != null) ? args.getEstrategia() : args.getModelo();

        estrategiaService.iniciarTradeRT(args.getEstrategia(), args.getModelo(), args.getTimeframe(),
                args.getCoins(), args.isReal(), walletID, risk, capitalAsignado);
        uiPrintln("Bot lanzado en segundo plano con éxito.");
    }

    private void ejecutarTrain(String[] parts) {
        if (!validarLogin()) {
            return;
        }

        CommandParser args = new CommandParser(parts);

        if (args.hasErrorSintaxis()) {
            uiPrintln(args.getMensajeError());
            return;
        }

        if (args.getModelo() == null || args.getTimeframe() == null || args.getCoins().isEmpty()) {
            uiPrintln("Uso: train -model <modelo> -tf <timeframe> -coins <coin>");
            uiPrintln("Ejemplo: train -model random_forest -tf 1h -coins BTCUSDT");
            return;
        }

        // NOTA: Tu método de entrenamiento actual solo acepta una moneda, así que
        // cogemos la primera.
        String coin = args.getCoins().get(0);

        uiPrintln("Iniciando pipeline de Inteligencia Artificial...");
        String resultado = aiTrainingService.entrenarModelo(args.getModelo(), args.getTimeframe(), coin);

        uiPrintln("\n--- RESULTADOS DEL MODELO ---");
        uiPrintln(resultado);
        uiPrintln("-----------------------------");
    }

    public void conseguirDatos(String[] parts) {
        CommandParser args = new CommandParser(parts);

        if (args.hasErrorSintaxis() || args.getTimeframe() == null || args.getCoins().isEmpty()) {
            uiPrintln("Uso: cbi -tf <timeframe> -coins <symbol> (Ej: cbi -tf 1h -coins BTCUSDT)");
            return;
        }

        String symbol = args.getCoins().get(0);
        marketDataService.calcularIndicadoresParaSimbolo(symbol, args.getTimeframe());
        uiPrintln("Datos de mercado e indicadores calculados para " + symbol);
    }

    private void ejecutarStart(String[] parts) {
        if (!validarLogin()) {
            return;
        }
        if (parts.length < 2) {
            uiPrintln("Uso: start <ID>  o  start -all");
            return;
        }
        if (parts[1].equalsIgnoreCase("-all")) {
            estrategiaService.iniciarTodasDetenidas();
            uiPrintln("Solicitud de inicio masivo enviada.");
        } else {
            try {
                Long id = Long.parseLong(parts[1]);
                estrategiaService.iniciarEstrategiaDetenida(id);
                uiPrintln("Estrategia " + id + " iniciada.");
            } catch (NumberFormatException e) {
                uiPrintln("El ID debe ser un numero.");
            } catch (Exception e) {
                uiPrintln("Error: " + e.getMessage());
            }
        }
    }

    private void ejecutarStop(String[] parts) {
        if (!validarLogin()) {
            return;
        }
        if (parts.length < 2) {
            uiPrintln("Uso: stop <ID>  o  stop -all");
            return;
        }
        if (parts[1].equalsIgnoreCase("-all")) {
            estrategiaService.detenerTodas();
            uiPrintln("Todas las estrategias activas han sido pausadas.");
        } else {
            try {
                Long id = Long.parseLong(parts[1]);
                estrategiaService.detenerEstrategia(id);
                uiPrintln("Estrategia " + id + " detenida.");
            } catch (NumberFormatException e) {
                uiPrintln("El ID debe ser un numero.");
            }
        }
    }

    private void ejecutarTerm(String[] parts) {
        if (!validarLogin()) {
            return;
        }
        if (parts.length < 2) {
            uiPrintln("Uso: term <ID>  o  term -all (Cuidado: Liquida todo)");
            return;
        }
        if (parts[1].equalsIgnoreCase("-all")) {
            uiPrint("¿Seguro que quieres LIQUIDAR TODAS las estrategias? (s/n): ");
            String confirm = scanner.nextLine().trim();
            if (confirm.equalsIgnoreCase("s")) {
                estrategiaService.terminarTodas();
                uiPrintln("Todas las estrategias han sido liquidadas.");
            } else {
                uiPrintln("Operacion cancelada.");
            }
        } else {
            try {
                Long id = Long.parseLong(parts[1]);
                estrategiaService.terminarEstrategia(id);
                uiPrintln("Estrategia " + id + " liquidada.");
            } catch (NumberFormatException e) {
                uiPrintln("El ID debe ser un numero.");
            } catch (Exception e) {
                log.error("Fallo al terminar", e);
                uiPrintln("Error critico: " + e.getMessage());
            }
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

    private boolean validarLogin() {
        if (!sessionManager.isLoggedIn()) {
            log.error("Acceso denegado. Debes hacer 'login' primero.");
            return false;
        }
        return true;
    }

    private void mostrarAyuda() {
        uiPrintln("""
                -----------------------------------------------------
                 COMANDOS DISPONIBLES
                -----------------------------------------------------
                 [USUARIO]  signup, login, logout
                 [WALLETS]  mkpwallet <nombre>, lw
                 [DATOS]    fetch <tf> <coin1> ...
                 [TRADING]  le (Listar scripts), lsa (Activas), lsd (Detenidas)
                            backtest <estra> <tf> <coins...>
                            trade -v|-r <estra> <tf> <coins...>
                            stop <ID> | stop -all
                            term <ID> (Liquidar y devolver fondos)
                 [SISTEMA]  exit, quit, ayuda
                -----------------------------------------------------
                """);
    }
}