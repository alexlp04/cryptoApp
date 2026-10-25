package com.bottrading;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.util.Arrays;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class AppBotTest {

    private final InputStream originalIn = System.in;

    @AfterEach
    void restoreStdin() {
        System.setIn(originalIn);
    }

    @Test
    @DisplayName("Los espacios repetidos no generan tokens vacíos (#104)")
    void tokenizer_collapses_repeated_spaces() {
        assertArrayEquals(new String[] {"trade", "-v", "-tf", "1h"}, AppBot.tokenizar("  trade  -v   -tf 1h "));
    }

    @Test
    @DisplayName("Con stdin cerrado el arranque no falla: espera como servicio hasta la señal de parada (#104)")
    void closed_stdin_keeps_the_process_alive_instead_of_crashing() throws Exception {
        System.setIn(new ByteArrayInputStream(new byte[0]));
        Constructor<?> ctor = AppBot.class.getDeclaredConstructors()[0];
        Object[] deps = Arrays.stream(ctor.getParameterTypes()).map(Mockito::mock).toArray();
        AppBot bot = (AppBot) ctor.newInstance(deps);
        bot.initializeCommandRegistry();

        Throwable[] failure = new Throwable[1];
        Thread t = new Thread(() -> {
            try {
                bot.run();
            } catch (Throwable e) {
                failure[0] = e;
            }
        });
        t.start();
        t.join(500);

        assertTrue(t.isAlive(), "debe quedarse esperando, no terminar ni lanzar NoSuchElementException");
        t.interrupt();
        t.join(2000);
        assertFalse(t.isAlive());
        assertTrue(failure[0] == null, "no debe lanzar: " + failure[0]);
    }
}
