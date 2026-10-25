package com.bottrading.interfaces.cli.commands;

import com.bottrading.interfaces.cli.CliCommandContext;
import com.bottrading.interfaces.cli.CliInputValidator;
import com.bottrading.shared.exceptions.ValidationException;

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
        String pass = CliInputValidator.readPassword(context, "Password: ");

        try {
            context.createUsuarioUseCase().registrar(nombre, pass);
        } catch (ValidationException | IllegalArgumentException e) {
            context.println().accept("No se pudo registrar: " + e.getMessage());
            return;
        }

        context.println().accept("Registro exitoso.");
    }
}
