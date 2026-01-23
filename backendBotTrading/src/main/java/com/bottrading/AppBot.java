package com.bottrading;

import com.bottrading.services.*;
import com.bottrading.beans.*;
import com.bottrading.utils.SessionManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

@Component
public class AppBot implements CommandLineRunner {

    @Autowired
    private EstrategiaService estrategiaService;
    @Autowired
    private MarketDataService marketDataService;
    @Autowired
    private UsuarioService usuarioService;
    @Autowired
    private WalletService walletService;
    @Autowired
    private SessionManager sessionManager;

    private final Scanner scanner = new Scanner(System.in);

    @Override
    public void run(String... args) throws Exception {
        System.out.println("=================================================");
        System.out.println("   BACKEND BOT TRADING - SPRING BOOT ENGINE      ");
        System.out.println("=================================================");
        System.out.println("Escribe 'ayuda' para ver los comandos.");

        while (true) {
            System.out.print("> ");
            String linea = scanner.nextLine().trim();
            if (linea.isEmpty())
                continue;
            if (linea.equalsIgnoreCase("exit"))
                break;

            procesarComando(linea);
        }
    }

    private void procesarComando(String comando) {
        String[] parts = comando.split(" ");
        String cmd = parts[0];

        try {
            switch (cmd) {
                // --- GESTIÓN DE USUARIOS ---
                case "signup" -> flujoSignup();
                case "login" -> flujoLogin();

                // --- GESTIÓN DE WALLETS ---
                case "mkpwallet" -> {
                    validarLogin();
                    // Usamos BigDecimal para el balance inicial
                    walletService.crearWallet(parts[1], new BigDecimal("10000.00"), false);
                    System.out.println("✅ Wallet de papel '" + parts[1] + "' creada con 10,000 USD.");
                }
                case "lw" -> {
                    validarLogin();
                    System.out.println("--- Tus Billeteras ---");
                    walletService.listarWallets().forEach(System.out::println);
                }

                // --- DATOS Y ESTRATEGIAS ---
                case "le" -> {
                    System.out.println("--- Estrategias Disponibles (.py) ---");
                    estrategiaService.listarEstrategias().forEach(System.out::println);
                }
                case "fetch" -> {
                    marketDataService.actualizarDatosMercado(Arrays.asList(parts[2].split(",")), parts[1]);
                    System.out.println("✅ Sincronización completa (Velas + Indicadores).");
                }

                // --- OPERACIONES DE TRADING ---
                case "backtest" -> {
                    if (parts.length < 4)
                        throw new RuntimeException("Uso: backtest <estrategia> <tf> <coin1>...");
                    List<String> coins = Arrays.asList(Arrays.copyOfRange(parts, 3, parts.length));
                    estrategiaService.ejecutarBacktest(parts[1], parts[2], coins);
                }
                case "trade" -> iniciarFlujoTrade(parts);

                case "ayuda" -> mostrarAyuda();
                default -> System.out.println("❓ Comando desconocido. Escribe 'ayuda'.");
            }
        } catch (Exception e) {
            System.err.println("❌ Error: " + e.getMessage());
        }
    }

    private void iniciarFlujoTrade(String[] parts) throws Exception {
        validarLogin();
        if (parts.length < 5) {
            System.out.println("Uso: trade -v|-r <estrategia> <timeframe> coin1 coin2...");
            return;
        }

        boolean isReal = parts[1].equalsIgnoreCase("-r");
        String estraNombre = parts[2];
        String tf = parts[3];
        List<String> coins = Arrays.asList(Arrays.copyOfRange(parts, 4, parts.length));

        // 1. Selección de Wallet
        System.out.println("\nSelecciona una wallet:");
        walletService.listarWallets().forEach(System.out::println);
        System.out.print("Nombre de la wallet: ");
        String wName = scanner.nextLine().trim();

        // 2. Lógica de Silos con BigDecimal
        BigDecimal balanceTotal = walletService.getBalance(wName);
        BigDecimal comprometido = estrategiaService.getCapitalComprometido(wName);
        BigDecimal disponible = balanceTotal.subtract(comprometido);

        System.out.printf("💰 Saldo Real: %s | Comprometido en Silos: %s | LIBRE: %s USD%n",
                balanceTotal.toPlainString(), comprometido.toPlainString(), disponible.toPlainString());

        // 3. Asignación de presupuesto
        System.out.print("Capital a asignar a este nuevo hilo: ");
        BigDecimal capitalAsignado = new BigDecimal(scanner.nextLine().trim());

        if (capitalAsignado.compareTo(disponible) > 0) {
            throw new RuntimeException("No tienes suficiente saldo disponible en esa wallet.");
        }

        System.out.print("Riesgo por trade (ej: 0.02 para 2%): ");
        BigDecimal risk = new BigDecimal(scanner.nextLine().trim());

        // 4. Lanzamiento
        Long walletId = walletService.obtenerIdPorNombre(wName);
        estrategiaService.iniciarTradeRT(estraNombre, tf, coins, isReal, walletId, risk, capitalAsignado);

        System.out.println("🚀 Estrategia lanzada. Monitoreando en hilos de fondo.");
    }

    // --- MÉTODOS AUXILIARES ---

    private void flujoSignup() {
        System.out.print("Nuevo Usuario: ");
        String nombre = scanner.nextLine().trim();
        System.out.print("Password: ");
        String pass = scanner.nextLine().trim();
        if (usuarioService.registrar(nombre, pass) == null) {
            System.out.println("❌ No se pudo registrar el usuario.");
            return;
        }
        System.out.println("✅ Registro exitoso.");
    }

    private void flujoLogin() {
        System.out.print("Usuario: ");
        String nombre = scanner.nextLine().trim();
        System.out.print("Password: ");
        String pass = scanner.nextLine().trim();

        if (usuarioService.validarCredenciales(nombre, pass)) {
            Usuario u = usuarioService.obtenerPorNombre(nombre);
            sessionManager.login(u);
            System.out.println("🔓 Sesión iniciada como " + u.getNombre());
        } else {
            System.out.println("❌ Credenciales incorrectas.");
        }
    }

    private void validarLogin() {
        if (!sessionManager.isLoggedIn()) {
            throw new RuntimeException("Acceso denegado. Debes hacer 'login' primero.");
        }
    }

    private void mostrarAyuda() {
        System.out.println("""
                ----------------------------------------------------------------------
                COMANDOS DISPONIBLES:
                ----------------------------------------------------------------------
                [USUARIO]  signup, login
                [WALLETS]  mkpwallet <nombre>, lw
                [DATOS]    fetch <simbolo> <timeframe> (Descarga velas e indicadores)
                [TRADING]  le (Listar archivos .py),
                           backtest <archivo> <tf> <coins...>
                           trade -v|-r <archivo> <tf> <coins...>
                [SISTEMA]  exit, ayuda
                ----------------------------------------------------------------------
                """);
    }
}