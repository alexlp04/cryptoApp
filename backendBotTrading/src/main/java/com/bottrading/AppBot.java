package com.bottrading;

import com.bottrading.Utils.WebSession;
import com.bottrading.controllers.ControladorEstrategia;
import com.bottrading.controllers.ControladorIndicador;
import com.bottrading.controllers.ControladorVela;
import com.bottrading.controllers.ControladorWallet;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.Scanner;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class AppBot {

    private static ControladorEstrategia controladorEstrategia;
    private static ControladorWallet controladorWallet;

    public static void main(String[] args) {
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
        System.setProperty("org.slf4j.simpleLogger.showThreadName", "false");
        System.setProperty("org.slf4j.simpleLogger.showLogName", "false");
        Logger.getLogger("org.hibernate").setLevel(Level.OFF);
        Scanner scanner = new Scanner(System.in);

        System.out.println(
                "Bienvenido al programa de comandos. Escribe 'ayuda' para ver opciones, o 'exit' para terminar.");

        while (true) {
            System.out.print("> ");
            String comando = scanner.nextLine().trim();
            if (comando.isEmpty())
                continue;

            String[] parts = comando.split(" ");
            String cmd = parts[0];

            if (cmd.equals("exit")) {
                System.out.println("Saliendo del programa...");
                break;
            }

            switch (cmd) {

                case "ayuda":
                    System.out.println(
                            """
                                    Comandos disponibles:
                                    - Usuario: signup, login
                                    - Wallet: mkpwallet <nombre>, rmpwallet <nombre>, active-wallet <nombre>, unset-wallet <nombre>, lw
                                    - Estrategias: le
                                    - Datos: fetch <symbol> <interval>, cbi <symbol> <interval>
                                    - Trading: backtest <estrategia> <timeframe> coin1 [coin2...], trade -v|-r <estrategia.py> <timeframe> <capital> <risk> coin1 [coin2...]
                                    - exit, ayuda
                                    """);
                    break;

                case "signup":
                    if (WebSession.getInstance().isLoggedIn()) {
                        System.out.println(
                                "Ya hay un usuario logueado: " + WebSession.getInstance().getCurrentUser().getNombre());
                        break;
                    }
                    System.out.print("Nombre: ");
                    String nombre = scanner.nextLine().trim();
                    System.out.print("Password: ");
                    String password = scanner.nextLine().trim();
                    if (WebSession.getInstance().signup(nombre, password)) {
                        System.out.println("Usuario creado exitosamente.");
                        controladorEstrategia = new ControladorEstrategia();
                        controladorWallet = new ControladorWallet();
                        controladorWallet.crearWallet("default", 10000.0, false);
                        System.out.println("Wallet 'default' creada y activa con 10000 USD Paper.");
                    } else {
                        System.out.println("Error al crear el usuario.");
                    }
                    break;

                case "login":
                    if (WebSession.getInstance().isLoggedIn()) {
                        System.out.println(
                                "Ya hay un usuario logueado: " + WebSession.getInstance().getCurrentUser().getNombre());
                        break;
                    }
                    System.out.print("Nombre: ");
                    String loginNombre = scanner.nextLine().trim();
                    System.out.print("Password: ");
                    String loginPassword = scanner.nextLine().trim();
                    if (WebSession.getInstance().login(loginNombre, loginPassword)) {
                        controladorWallet = new ControladorWallet();
                        controladorEstrategia = new ControladorEstrategia();
                    } else {
                        System.out.println("Error en el login. Credenciales incorrectas.");
                    }
                    break;

                // --- Wallet commands ---
                case "mkpwallet":
                    if (!checkLogin())
                        break;
                    if (parts.length < 2) {
                        System.out.println("Uso: mkpwallet <nombre>");
                        break;
                    }
                    String newWallet = parts[1];
                    controladorWallet.crearWallet(newWallet, 10000.0, false);
                    System.out.println("Wallet Paper '" + newWallet + "' creada con 10000 USD.");
                    break;

                case "rmpwallet":
                    if (!checkLogin())
                        break;
                    if (parts.length < 2) {
                        System.out.println("Uso: rmpwallet <nombre>");
                        break;
                    }
                    String delWallet = parts[1];
                    controladorWallet.eliminarWallet(delWallet);
                    System.out.println("Wallet '" + delWallet + "' eliminada.");
                    break;

                case "active-wallet":
                    if (!checkLogin())
                        break;
                    if (parts.length < 2) {
                        System.out.println("Uso: active-wallet <nombre>");
                        break;
                    }
                    String activeWallet = parts[1];
                    controladorWallet.setWalletActiva(activeWallet);
                    System.out.println("Wallet '" + activeWallet + "' seleccionada como activa.");
                    break;

                case "unset-wallet":
                    if (!checkLogin())
                        break;
                    if (parts.length < 2) {
                        System.out.println("Uso: unset-wallet <nombre>");
                        break;
                    }
                    String disactiveWallet = parts[1];
                    controladorWallet.unsetWalletActiva(disactiveWallet);
                    System.out.println("Wallet activa desactivada.");
                    break;

                case "lw": // listar wallets
                    if (!checkLogin())
                        break;
                    controladorWallet.listarWallets().forEach(System.out::println);
                    break;

                case "le": // listar estrategias
                    controladorEstrategia.listarEstrategias().forEach(System.out::println);
                    break;
                // --- Otros comandos existentes ---
                case "fetch":
                    if (parts.length < 3) {
                        System.out.println("Uso: fetch <symbol> <interval>");
                        break;
                    }
                    ControladorVela.actualizarDatos(parts[1], parts[2]);
                    break;

                case "cbi":
                    if (parts.length < 3) {
                        System.out.println("Uso: cbi <symbol> <interval>");
                        break;
                    }
                    ControladorIndicador.calcularIndicadoresBasicos(parts[1], parts[2]);
                    break;

                case "backtest":
                    if (parts.length < 4) {
                        System.out.println("Uso: backtest <estrategia> <timeframe> coin1 [coin2...]");
                        break;
                    }
                    try {
                        LinkedList<String> coins = new LinkedList<>(
                                Arrays.asList(Arrays.copyOfRange(parts, 3, parts.length)));
                        controladorEstrategia.backtestEstrategia(parts[1], parts[2], coins);
                    } catch (Exception e) {
                        System.out.println("Error al ejecutar la estrategia: " + e.getMessage());
                    }
                    break;

                case "trade":
                    if (!checkLogin())
                        break;
                    if (parts.length < 4) {
                        System.out.println("Uso: trade -v|-r <estrategia> <timeframe> coin1 [coin2...]");
                        break;
                    }

                    boolean isReal = parts[1].equals("-r");
                    String estrategia = parts[2];
                    String timeframe = parts[3];
                    LinkedList<String> coins = new LinkedList<>(
                            Arrays.asList(Arrays.copyOfRange(parts, 4, parts.length)));

                    ejecutarTrade(isReal, estrategia, timeframe, coins, scanner);
                    break;
                default:
                    System.out.println("Comando desconocido: " + comando);
            }
        }

        scanner.close();
    }

    private static boolean checkLogin() {
        if (!WebSession.getInstance().isLoggedIn()) {
            System.out.println("Debes loguearte primero.");
            return false;
        }
        return true;
    }

    private static void ejecutarTrade(boolean isReal, String estrategia, String timeframe, 
                                    LinkedList<String> coins, Scanner mainScanner) {

        // 1. Wallets disponibles
        LinkedList<String> wallets = new LinkedList<>(controladorWallet.listarWallets());
        if (wallets.isEmpty()) return;

        // 2. Selección de Wallet
        String walletSeleccionada = seleccionarWallet(wallets, mainScanner);
        if (walletSeleccionada == null) return;

        // 3. NUEVO: Validación de Capital Disponible (Silo)
        double balanceTotal = controladorWallet.getBalance(walletSeleccionada);
        double capitalComprometido = controladorEstrategia.getCapitalComprometido(walletSeleccionada);
        double saldoLibre = balanceTotal - capitalComprometido;

        System.out.printf("Balance Total: %.2f | Comprometido: %.2f | DISPONIBLE: %.2f USD%n", 
                        balanceTotal, capitalComprometido, saldoLibre);
        
        System.out.print("Capital a asignar a esta instancia: ");
        double capitalAsignar = Double.parseDouble(mainScanner.nextLine().trim());

        if (capitalAsignar <= 0 || capitalAsignar > saldoLibre) {
            System.out.println("Capital inválido o insuficiente saldo libre.");
            return;
        }

        // 4. Obtener risk
        double risk = pedirRisk(mainScanner);
        if (risk <= 0) return;

        // 5. Ejecución
        try {
            // Marcamos la wallet como activa para el usuario (opcional si permites múltiples)
            controladorWallet.setWalletActiva(walletSeleccionada);
            
            controladorEstrategia.tradeRT(estrategia, timeframe, coins, isReal, walletSeleccionada, risk, capitalAsignar);

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
        }
    }

    private static String seleccionarWallet(LinkedList<String> wallets, Scanner scanner) {
        System.out.println("\n Wallets disponibles:");
        wallets.forEach(w -> System.out.println("  • " + w));

        LinkedList<String> nombres = wallets.stream()
                .map(w -> w.split(" \\| ")[0])
                .collect(Collectors.toCollection(LinkedList::new));

        System.out.print("\nSelecciona wallet: ");
        String input = scanner.nextLine().trim();

        if (nombres.contains(input)) {
            return input;
        } else {
            System.out.println("Wallet inválida o ya en uso.");
            return null;
        }
    }

    private static double pedirRisk(Scanner scanner) {
        System.out.print("Risk por operación (0.01 = 1%, 0.02 = 2%): ");
        try {
            double risk = Double.parseDouble(scanner.nextLine().trim());
            if (risk > 0 && risk <= 1.0) {
                return risk;
            }
        } catch (NumberFormatException e) {
            // ignorar
        }
        System.out.println("Risk inválido. Debe estar entre 0 y 1.");
        return -1;
    }
}
