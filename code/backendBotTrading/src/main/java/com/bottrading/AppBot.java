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
                case "models" -> mostrarMenuModelos();

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

        uiPrint("Capital a asignar: ");
        BigDecimal capitalAsignado = new BigDecimal(scanner.nextLine().trim());

        uiPrint("Riesgo por trade (0.01 - 1.0): ");
        BigDecimal risk = new BigDecimal(scanner.nextLine().trim());

        uiPrintln("Iniciando Backtest...");
        estrategiaService.ejecutarBacktest(args.getEstrategia(), args.getTimeframe(), args.getCoins(), capitalAsignado, risk);
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

        // TODO: Hacer que pueda entrenar con varias monedas a la vez
        String coin = args.getCoins().get(0);

        int diasEntrenamiento = validarYCalcularDias(args.getTimeframe(), args.getDays());
        if (diasEntrenamiento == -1) {
            uiPrintln("Operación cancelada.");
            return;
        }

        uiPrintln("Iniciando pipeline de Inteligencia Artificial...");
        String resultado = aiTrainingService.entrenarModelo(
                            args.getModelo(), 
                            args.getTimeframe(), 
                            coin, 
                            diasEntrenamiento,
                            args.getHyperparams() 
                    );
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

    private int validarYCalcularDias(String tf, Integer requestedDays) {
        int maxDays;
        int defaultDays;

        // Establecemos límites lógicos según la temporalidad
        switch (tf.toLowerCase()) {
            case "1m":
                maxDays = 180;
                defaultDays = 30;
                break; // Máx 6 meses
            case "5m":
                maxDays = 365;
                defaultDays = 90;
                break; // Máx 1 año
            case "15m":
                maxDays = 730;
                defaultDays = 180;
                break; // Máx 2 años
            case "1h":
                maxDays = 1095;
                defaultDays = 365;
                break; // Máx 3 años
            case "4h":
                maxDays = 1825;
                defaultDays = 730;
                break; // Máx 5 años
            case "1d":
                maxDays = 3650;
                defaultDays = 1095;
                break;// Máx 10 años
            default:
                maxDays = 365;
                defaultDays = 90;
                break;
        }

        // Si el usuario no puso la bandera -d, usamos el default
        if (requestedDays == null) {
            uiPrintln("No se especificaron días (-d). Usando valor recomendado para " + tf + ": " + defaultDays
                    + " días.");
            return defaultDays;
        }

        // Si el usuario se pasó de la raya, le avisamos antes de explotar su RAM
        if (requestedDays > maxDays) {
            uiPrintln("ADVERTENCIA: Para el timeframe " + tf + ", el máximo recomendado es " + maxDays + " días.");
            uiPrintln("Usar " + requestedDays
                    + " días podría provocar un error de Memoria (Out Of Memory) y confundir a la IA.");
            uiPrint("¿Estás seguro de que quieres intentar continuar? (s/n): ");
            String confirm = scanner.nextLine().trim();
            if (!confirm.equalsIgnoreCase("s")) {
                return -1; // Código de cancelación
            }
        }

        return requestedDays;
    }

private void mostrarMenuModelos() {
        uiPrintln("\n=================================================================================");
        uiPrintln("   CATÁLOGO DE MODELOS DE INTELIGENCIA ARTIFICIAL Y SUS HIPERPARÁMETROS   ");
        uiPrintln("=================================================================================\n");
        
        uiPrintln("Uso en entrenamiento: train -m <modelo> -tf 15m -c BTCUSDT -params k1=v1,k2=v2\n");

        uiPrintln("   1. RANDOM FOREST (-m random_forest) [Recomendado para empezar]");
        uiPrintln("   El más robusto. Crea múltiples árboles de decisión y votan el resultado.");
        uiPrintln("   > n_estimators (Int) : Número de árboles (100-500). Def: 100");
        uiPrintln("   > max_depth    (Int) : Profundidad máxima del árbol (5-20). Def: 10");
        uiPrintln("   > min_samples_split (Int): Mínimo de velas para crear rama (2-20).\n");

        uiPrintln("   2. XGBOOST (-m xgboost) [Alta Precisión]");
        uiPrintln("   El rey del Machine Learning. Muy potente pero propenso a memorizar ruido.");
        uiPrintln("   > n_estimators  (Int)  : Iteraciones de aprendizaje (100-1000). Def: 150");
        uiPrintln("   > learning_rate (Float): Tasa de aprendizaje (0.01-0.2). Def: 0.05");
        uiPrintln("   > max_depth     (Int)  : Profundidad (3-10). Def: 6");
        uiPrintln("   > gamma         (Float): Filtro anti-ruido, reducción mínima (0.0-5.0).\n");

        uiPrintln("   3. LIGHTGBM (-m lightgbm) [Máxima Velocidad]");
        uiPrintln("   Ideal para entrenar años de datos en temporalidades pequeñas (1m, 5m).");
        uiPrintln("   > num_leaves    (Int)  : Hojas por árbol (20-100). Def: 31");
        uiPrintln("   > learning_rate (Float): Tasa de aprendizaje (0.01-0.2). Def: 0.05");
        uiPrintln("   > max_depth     (Int)  : Profundidad (3-12). Def: 6\n");

        uiPrintln("   4. GRADIENT BOOSTING (-m gradient_boosting) [Clásico]");
        uiPrintln("   > n_estimators  (Int)  : (100-500). Def: 100");
        uiPrintln("   > learning_rate (Float): (0.01-0.2). Def: 0.1\n");

        uiPrintln("   5. SUPPORT VECTOR MACHINES (-m svm) [Matemático]");
        uiPrintln("   Detecta regímenes de mercado creando fronteras matemáticas.");
        uiPrintln("   > C             (Float): Margen de error (0.1-100). Def: 1.0");
        uiPrintln("   > kernel        (Str)  : Forma ('rbf', 'linear', 'poly'). Def: rbf\n");

        uiPrintln("   6. DEEP LEARNING / RED NEURONAL (-m neural_network) [Avanzado]");
        uiPrintln("   TensorFlow/Keras. Excelente si le pasas muchos indicadores.");
        uiPrintln("   > epochs        (Int)  : Vueltas completas al dataset (10-100). Def: 50");
        uiPrintln("   > batch_size    (Int)  : Velas procesadas de golpe (32, 64, 128). Def: 64");
        uiPrintln("   > learning_rate (Float): Velocidad de ajuste (0.001-0.0001).");
        uiPrintln("   > dropout_rate  (Float): Apaga neuronas para evitar sobreajuste (0.2-0.5).\n");
        
        uiPrintln("=================================================================================");
    }

}