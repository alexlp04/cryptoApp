package com.bottrading.interfaces.cli.commands;

import com.bottrading.user.domain.Usuario;
import com.bottrading.interfaces.cli.CliInputValidator;
import com.bottrading.interfaces.cli.CliCommandContext;

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
        context.print().accept("Password: ");
        String pass = context.scanner().nextLine().trim();

        if (context.usuarioService().validarCredenciales(nombre, pass)) {
            Usuario usuario = context.usuarioService().obtenerPorNombre(nombre);
            context.sessionManager().login(usuario);
            context.println().accept("Sesion iniciada como " + usuario.getNombre());
            return;
        }

        context.println().accept("Credenciales incorrectas.");
    }
}
