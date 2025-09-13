package com.bottrading;

import com.bottrading.Utils.WebSession;
import java.util.Scanner;

public class AppBot {
    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        System.out.println(
                "Bienvenido al programa de comandos. Escribe 'ayuda' para ver opciones, o 'salir' para terminar.");

        while (true) {
            System.out.print("> ");
            String comando = scanner.nextLine().trim().toLowerCase();

            if (comando.equals("salir")) {
                System.out.println("Saliendo del programa...");
                break;
            }

            switch (comando) {
                case "ayuda":
                    System.out.println("Comandos disponibles: ayuda, signup, login, salir");
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
                default:
                    System.out.println("Comando desconocido: " + comando);
            }
        }

        scanner.close();
    }
}
