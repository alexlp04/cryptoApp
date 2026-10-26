package com.bottrading.interfaces.cli.commands;

import java.lang.reflect.Constructor;
import java.util.Scanner;
import java.util.function.Consumer;

import org.mockito.Mockito;

import com.bottrading.interfaces.cli.CliCommandContext;

/** Construye un {@link CliCommandContext} de dobles: todo es un mock salvo lo que se pasa en {@code overrides}. */
final class CliTestContexts {

    private CliTestContexts() {
    }

    /**
     * @param input     lo que «teclea» el usuario
     * @param out       recoge todo lo que la CLI imprime
     * @param overrides pares (posición del parámetro en el contexto, objeto) que sustituyen al mock
     */
    static CliCommandContext create(String input, Consumer<String> out, Object... overrides) throws Exception {
        Constructor<?> ctor = CliCommandContext.class.getDeclaredConstructors()[0];
        Class<?>[] types = ctor.getParameterTypes();
        Object[] args = new Object[types.length];
        for (int i = 0; i < args.length; i++) {
            args[i] = Consumer.class.equals(types[i]) ? out : Mockito.mock(types[i]);
        }
        args[0] = new Scanner(input);
        for (int i = 0; i < overrides.length; i += 2) {
            args[(Integer) overrides[i]] = overrides[i + 1];
        }
        return (CliCommandContext) ctor.newInstance(args);
    }
}
