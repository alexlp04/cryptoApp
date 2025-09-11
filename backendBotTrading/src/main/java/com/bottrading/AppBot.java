package com.bottrading;

import com.bottrading.beans.Usuario;
import com.bottrading.controllers.ControladorUsuario;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import java.util.List;
import java.util.Scanner;

public class AppBot {
  public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        System.out.println("Bienvenido al programa de comandos. Escribe 'ayuda' para ver opciones, o 'salir' para terminar.");

        while (true) {
            System.out.print("> ");
            String comando = scanner.nextLine().trim().toLowerCase();

            if (comando.equals("salir")) {
                System.out.println("Saliendo del programa...");
                break;
            }

            switch (comando) {
                case "ayuda":
                    System.out.println("Comandos disponibles: ayuda, signup, login, listar, salir");
                    break;

                case "signup":
                    String nombre, email, password;
                    System.out.print("Nombre: ");
                    nombre = scanner.nextLine();
                    System.out.print("Email: ");
                    email = scanner.nextLine();
                    System.out.print("Password: ");
                    password = scanner.nextLine();
                    ControladorUsuario controlador = new ControladorUsuario();
                    if (!controlador.existeUsuario(email)) {
                        Usuario user = controlador.crearUsuario(email, nombre, password);
                        if (user != null) {
                            System.out.println("Usuario creado exitosamente.");
                        } else {
                            System.out.println("Error al crear el usuario. El email puede estar en uso.");
                        }
                    } else {
                        System.out.println("El usuario ya existe.");
                    }
                    break;

                case "login":
                    System.out.print("Email: ");
                    String loginEmail = scanner.nextLine();
                    System.out.print("Password: ");
                    String loginPassword = scanner.nextLine();
                   // TODO: Implementar lógica de login
                   break;   

                case "listar":
                    // TODO
                    break;

                default:
                    System.out.println("Comando desconocido: " + comando);
            }
        }

        scanner.close();
    }
}

