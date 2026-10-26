package com.bottrading.interfaces.cli.commands;

import java.util.Optional;

import com.bottrading.interfaces.cli.CliCommandContext;
import com.bottrading.interfaces.cli.CliInputValidator;
import com.bottrading.user.domain.Usuario;

/**
 * Handles user login from CLI.
 */
public final class LoginCommand implements CliCommand {

    @Override
    public String name() {
        return "login";
    }

    @Override
    public void execute(String[] parts, CliCommandContext context) {
        if (!CliInputValidator.requireLoggedOut(context)) {
            return;
        }

        context.print().accept("Usuario: ");
        String nombre = context.scanner().nextLine().trim();
        String pass = CliInputValidator.readPassword(context, "Password: ");

        Optional<Usuario> usuario = context.authenticateUseCase().autenticar(nombre, pass);
        if (usuario.isPresent()) {
            context.sessionManager().login(usuario.get());
            context.println().accept("Sesion iniciada como " + usuario.get().getNombre());
            return;
        }

        context.println().accept("Credenciales incorrectas.");
    }
}
