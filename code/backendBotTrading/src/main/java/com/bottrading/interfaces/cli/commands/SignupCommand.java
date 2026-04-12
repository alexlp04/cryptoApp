package com.bottrading.interfaces.cli.commands;

import com.bottrading.interfaces.cli.CliCommandContext;
import com.bottrading.interfaces.cli.CliInputValidator;

/**
 * Handles user signup from CLI.
 */
public final class SignupCommand implements CliCommand {

    @Override
    public String name() {
        return "signup";
    }

    @Override
    public void execute(String[] parts, CliCommandContext context) {
        if (!CliInputValidator.requireLoggedOut(context)) {
            return;
        }

        context.print().accept("Nuevo Usuario: ");
        String nombre = context.scanner().nextLine().trim();
        context.print().accept("Password: ");
        String pass = context.scanner().nextLine().trim();

        if (context.createUsuarioUseCase().registrar(nombre, pass) == null) {
            context.println().accept("No se pudo registrar (quizas el usuario ya existe).");
            return;
        }

        context.println().accept("Registro exitoso.");
    }
}
