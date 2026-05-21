package com.bottrading.shared.utils;

public class ConsoleLoader {

    private Thread loaderThread;
    private volatile boolean running;
    private volatile String currentMessage;
    private volatile int frameDelayMillis;

    private static ConsoleLoader instance;

    private ConsoleLoader() {
        running = false;
        currentMessage = "";
        frameDelayMillis = 250;
    }

    // Hacemos el Singleton Thread-Safe
    public static synchronized ConsoleLoader getInstance() {
        if (instance == null) {
            instance = new ConsoleLoader();
        }
        return instance;
    }

    /**
     * Tipo 1: puntos animados con mensaje personalizable
     */
    public void startDots(String message) {
        currentMessage = message == null ? "" : message;
        frameDelayMillis = 400;
        start(() -> {
            String[] dots = { ".  ", ".. ", "..." };
            int i = 0;
            while (running) {
                System.out.print("\r" + currentMessage + dots[i % dots.length]);
                i++;
                sleep(frameDelayMillis);
            }
        });
    }

    /**
     * Tipo 2: spinner giratorio con mensaje personalizable
     */
    public void startSpinner(String message) {
        currentMessage = message == null ? "" : message;
        frameDelayMillis = 200;
        start(() -> {
            char[] spinner = { '|', '/', '-', '\\' };
            int i = 0;
            while (running) {
                System.out.print("\r" + currentMessage + " " + spinner[i % spinner.length]);
                i++;
                sleep(frameDelayMillis);
            }
        });
    }

    /**
     * Actualiza en caliente el mensaje mostrado por la animación activa.
     * Si no hay animación en ejecución, no hace nada.
     */
    public void updateMessage(String message) {
        if (!running) {
            return;
        }
        currentMessage = message == null ? "" : message;
    }

    /**
     * Detiene la animación y muestra un mensaje final.
     * Si finalMessage es nulo o vacío, la línea queda limpia.
     */
    public void stop(String finalMessage) {
        running = false;
        if (loaderThread != null && loaderThread.isAlive()) {
            try {
                loaderThread.join(); // espera a que el hilo termine su ciclo
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        loaderThread = null;
        
        // Sobreescribe la línea actual con espacios para borrar el rastro de la animación
        System.out.print("\r                                                                      \r");
        
        // Imprime el mensaje final si existe, si no, no hace nada
        if (finalMessage != null && !finalMessage.isEmpty()) {
            System.out.println(finalMessage);
        }
    }

    public void stopClear() { stop(""); } // Método de atajo para borrar todo

    /**
     * Función interna que inicia el hilo con la animación
     */
    private void start(Runnable animationLogic) {
        if (running) {
            return; // evita instanciar múltiples hilos
        }
        running = true;
        loaderThread = new Thread(animationLogic, "console-loader-thread");
        loaderThread.setDaemon(true);
        loaderThread.start();
    }

    /**
     * Sleep seguro
     */
    private void sleep(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}