package com.bottrading.shared.utils;

public class ConsoleLoader {

    private Thread loaderThread;
    private volatile boolean running;

    private static ConsoleLoader instance;

    private ConsoleLoader() {
        running = false;
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
        start(() -> {
            String[] dots = { ".  ", ".. ", "..." };
            int i = 0;
            while (running) {
                System.out.print("\r" + message + dots[i % dots.length]);
                i++;
                sleep(400);
            }
        });
    }

    /**
     * Tipo 2: spinner giratorio con mensaje personalizable
     */
    public void startSpinner(String message) {
        start(() -> {
            char[] spinner = { '|', '/', '-', '\\' };
            int i = 0;
            while (running) {
                System.out.print("\r" + message + " " + spinner[i % spinner.length]);
                i++;
                sleep(200);
            }
        });
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
        if (running) return; // evita instanciar múltiples hilos
        running = true;
        loaderThread = new Thread(animationLogic);
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