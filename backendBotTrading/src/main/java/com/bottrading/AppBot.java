package com.bottrading;

import com.bottrading.Services.FetchService;
import com.bottrading.Utils.WebSession;

import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public class AppBot {
    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        System.out.println(
                "Bienvenido al programa de comandos. Escribe 'ayuda' para ver opciones, o 'salir' para terminar.");

        while (true) {
            System.out.print("> ");
            String comando = scanner.nextLine().trim();
            String[] parts = comando.split(" ");
            if (parts[0].equals("exit")) {
                System.out.println("Saliendo del programa...");
                break;
            }

            List<String> cmd = new ArrayList<>();
            cmd.add("python");

            switch (parts[0]) {

                case "ayuda":
                    System.out.println("Comandos disponibles: ayuda, signup, login, exit, fetch, backtest, trade");
                    break;

                case "signup":
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
                    FetchService.fetch(parts[1], parts[2]);
                    break;

                case "backtest":
                    // TODO: Implement backtesting logic
                    break;
                case "trade":
                    // TODO: Implement trading logic
                    break;
                default:
                    System.out.println("Comando desconocido: " + comando);
            }
        }

        scanner.close();
    }

}
