package com.bottrading.Utils;

public class ConsoleLoader {

    private Thread loaderThread;
    private volatile boolean running = false;

    private static ConsoleLoader instance = new ConsoleLoader();

    private ConsoleLoader() {}

    public static ConsoleLoader getInstance() {
        return instance;
    }

    /**
     * Tipo 1: puntos animados ". .. ..."
     */
    public void startDots() {
        start(() -> {
            String[] dots = {".  ", ".. ", "..."};
            int i = 0;
            while (running) {
                System.out.print("\rCargando" + dots[i % dots.length]);
                i++;
                sleep(400);
            }
        });
    }

    /**
     * Tipo 2: spinner giratorio "| / - \\"
     */
    public void startSpinner() {
        start(() -> {
            char[] spinner = {'|', '/', '-', '\\'};
            int i = 0;
            while (running) {
                System.out.print("\rCargando " + spinner[i % spinner.length]);
                i++;
                sleep(200);
            }
        });
    }

    /**
     * Detener animación
     */
    public void stop() {
        running = false;
        if (loaderThread != null && loaderThread.isAlive()) {
            try {
                loaderThread.join(); // espera a que termine
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        System.out.print("\rCarga completa!   \n"); // limpia línea final
    }

    /**
     * Función interna que inicia el hilo con la animación
     */
    private void start(Runnable animationLogic) {
        if (running) return; // ya está corriendo
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
