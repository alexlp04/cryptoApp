package com.bottrading.controllers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;

import com.bottrading.Services.SaveFileService;
import com.bottrading.Services.StrategyService;
import com.bottrading.Utils.ConsoleLoader;
import com.bottrading.beans.Vela;
import com.bottrading.daos.VelaDAO;

public class ControladorEstrategia {

    /**
     * Comprueba si existe el fichero de la estrategia
     */
    public Boolean existe(String rutaEstrategia) {
        Path path = Paths.get(rutaEstrategia);
        return Files.exists(path)
                && Files.isRegularFile(path)
                && rutaEstrategia.endsWith(".py");
    }

    public void ejecutarEstrategiaEnTiempoReal(String rutaEstrategia, String timeframe, String[] coins)
            throws Exception {

        if (!existe(rutaEstrategia)) {
            throw new Exception("La estrategia no existe: " + rutaEstrategia);
        }

        System.out.println("Ejecutando estrategia en tiempo real:");
        System.out.println("Ruta: " + rutaEstrategia);
        System.out.println("Timeframe: " + timeframe);
        System.out.println("Símbolos: " + String.join(", ", coins));

        // TODO: lanzar proceso Python en modo realtime
    }

    public void backtestEstrategia(String rutaEstrategia, String timeframe, List<String> coins)
            throws Exception {

        if (!existe(rutaEstrategia)) {
            throw new Exception("La estrategia no existe: " + rutaEstrategia);
        }

        HashMap<String, List<Vela>> velasPorSimbolo = new HashMap<>();
        for (String symbol : coins) {
            List<Vela> velas = VelaDAO.getInstance()
                    .findBySymbolAndInterval(symbol, timeframe);
            velasPorSimbolo.put(symbol, velas);
        }

        System.out.println("Haciendo backtest:");
        System.out.println("Ruta: " + rutaEstrategia);
        System.out.println("Timeframe: " + timeframe);
        System.out.println("Símbolos: " + String.join(", ", coins));
        ConsoleLoader loader = ConsoleLoader.getInstance();
        loader.startDots();

        StrategyService strategyService = new StrategyService();
        String resultado = strategyService.ejecutarBacktest(
        rutaEstrategia,
        timeframe,
        velasPorSimbolo
        );
        loader.stop();
        SaveFileService saveFileService = new SaveFileService();
        saveFileService.guardarCSVBacktest(rutaEstrategia, resultado);


    }

    public static void comprobarDatosParaEstrategia(String timeframe, List<String> coins) {
        coins.stream().forEach(symbol -> ControladorVela.actualizarDatos(symbol, timeframe));
        
    }
}
