package com.bottrading;

import com.bottrading.Utils.WebSession;
import com.bottrading.controllers.ControladorEstrategia;
import com.bottrading.controllers.ControladorIndicador;
import com.bottrading.controllers.ControladorVela;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.Scanner;

public class AppBot {
    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        System.out.println(
                "Bienvenido al programa de comandos. Escribe 'ayuda' para ver opciones, o 'salir' para terminar.");

        while (true) {
            System.out.print("> ");
            String comando = scanner.nextLine().trim();
            if (comando.isEmpty())
                continue;

            // Separar partes y detectar sudo
            String[] parts = comando.split(" ");

            // Salir del programa
            if (parts[0].equals("exit")) {
                System.out.println("Saliendo del programa...");
                break;
            }

            ControladorEstrategia controladorEstrategia;

            switch (parts[0]) {

                case "ayuda":
                    System.out.println("Comandos disponibles: ayuda, signup, login, exit, fetch, backtest, trade");
                    break;

                case "signup":
                    if (WebSession.getInstance().isLoggedIn()) {
                        System.out.println("Ya hay un usuario logueado: "
                                + WebSession.getInstance().getCurrentUser().getNombre());
                        break;
                    }
                    String nombre, email, password;
                    System.out.print("Nombre: ");
                    nombre = scanner.nextLine();
                    System.out.print("Email: ");
                    email = scanner.nextLine();
                    System.out.print("Password: ");
                    password = scanner.nextLine();
                    if (WebSession.getInstance().signup(nombre, email, password)) {
                        System.out.println("Usuario creado exitosamente.");
                    } else {
                        System.out.println("Error al crear el usuario.");
                    }
                    break;
                case "login":
                    if (WebSession.getInstance().isLoggedIn()) {
                        System.out.println("Ya hay un usuario logueado: "
                                + WebSession.getInstance().getCurrentUser().getNombre());
                        break;
                    }
                    System.out.print("Email: ");
                    String loginEmail = scanner.nextLine();
                    System.out.print("Password: ");
                    String loginPassword = scanner.nextLine();
                    if (WebSession.getInstance().login(loginEmail, loginPassword)) {
                        System.out.println("Login exitoso. Usuario actual: "
                                + WebSession.getInstance().getCurrentUser().getNombre());
                    } else {
                        System.out.println("Error en el login. Credenciales incorrectas.");
                    }
                    break;
                case "fetch":
                    if (parts.length < 2) {
                        System.out.println("Uso: fetch <symbol> <interval> ");
                        break;
                    }
                    ControladorVela.actualizarDatos(parts[1], parts[2]);
                    break;
                case "cbi":
                    if (parts.length < 2) {
                        System.out.println("Uso: cbi <symbol> <interval>");
                        break;
                    }
                    ControladorIndicador.calcularIndicadoresBasicos(parts[1], parts[2]);
                    break;

                case "backtest":
                    if (parts.length < 4) {
                        System.out.println("Uso: execute <name> <timeframe> coin1 [coin2] ...");
                        break;
                    }
                    controladorEstrategia = new ControladorEstrategia();
                    try {
                        LinkedList<String> coins = new LinkedList<>(Arrays.asList(Arrays.copyOfRange(parts, 3, parts.length)));
                        controladorEstrategia.backtestEstrategia(parts[1], parts[2], coins);
                    } catch (Exception e) {
                        System.out.println("Error al ejecutar la estrategia: " + e.getMessage());
                    }
                    break;
                case "trade":
                    if (!WebSession.getInstance().isLoggedIn()) {
                        System.out.println("Debes iniciar sesión primero.");
                        break;
                    }
                    if (parts.length < 2) {
                        System.out.println("Uso: execute <name> [args...]");
                        break;
                    }
                    controladorEstrategia = new ControladorEstrategia();
                    // TODO: pensar los tipos de parametros que se le pueden pasar a la estrategia
                    try {
                        controladorEstrategia.ejecutarEstrategiaEnTiempoReal(parts[1], parts[2],
                                Arrays.copyOfRange(parts, 3, parts.length));
                    } catch (Exception e) {
                        System.out.println("Error al ejecutar la estrategia: " + e.getMessage());
                    }
                    // TODO: Implementar logica post trading
                    break;
                default:
                    System.out.println("Comando desconocido: " + comando);
            }
        }

        scanner.close();
    }

}
