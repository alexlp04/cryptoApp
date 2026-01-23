package com.bottrading.controllers;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import com.bottrading.Services.BacktestingService;
import com.bottrading.Services.PaperTradingService;
import com.bottrading.Services.SaveFileService;
import com.bottrading.Services.StrategyService;
import com.bottrading.Utils.ConsoleLoader;
import com.bottrading.Utils.PathConfig;
import com.bottrading.beans.InstanciaEstrategia;
import com.bottrading.beans.Vela;
import com.bottrading.daos.InstanciaEstrategiaDAO;
import com.bottrading.daos.VelaDAO;

public class ControladorEstrategia {

    private final InstanciaEstrategiaDAO instanciaDAO = InstanciaEstrategiaDAO.getInstance();

    public InstanciaEstrategia iniciarNuevaInstancia(String nombreEstra, String tf, List<String> coins,
            boolean isReal, String wallet, double risk, double capital) throws Exception {

        // 1. Crear y Guardar la instancia en DB
        InstanciaEstrategia instancia = new InstanciaEstrategia();
        instancia.setNombreEstrategia(nombreEstra);
        instancia.setTimeframe(tf);
        instancia.setWalletAsociada(wallet);
        instancia.setCapitalAsignadoActual(capital);
        instancia.setRiskPerTrade(risk);
        instancia.setSimbolos(coins);
        instancia.setEsReal(isReal);
        instancia.setEstado("ACTIVA");

        instancia = instanciaDAO.save(instancia); // Guardamos en DB
        return instancia;   
    }

    public Boolean existe(String rutaEstrategia) {
        Path path = Paths.get(rutaEstrategia);
        return Files.exists(path)
                && Files.isRegularFile(path)
                && rutaEstrategia.endsWith(".py");
    }

    public void tradeRT(String nombreEstrategia, String timeframe, List<String> coins, boolean isReal,
            String nombreWallet, double riskPerTrade, double capital) throws Exception {
                
        InstanciaEstrategia instancia = iniciarNuevaInstancia(nombreEstrategia, timeframe, coins, isReal, nombreWallet, riskPerTrade, capital);
        String rutaEstrategia = PathConfig.getValidStrategyPath(nombreEstrategia);
        PaperTradingService paperTradingService = new PaperTradingService(instancia, nombreWallet);
        StrategyService strategyService = new StrategyService(paperTradingService);
        strategyService.ejecutarTradeEnTiempoReal(rutaEstrategia, timeframe, coins, isReal);
    }

    public double getCapitalComprometido(String nombreWallet) {
        return instanciaDAO.sumCapitalActivoByWallet(nombreWallet);
    }

    public void backtestEstrategia(String nombreEstrategia, String timeframe, List<String> coins) throws Exception {

        String rutaEstrategia = PathConfig.getValidStrategyPath(nombreEstrategia);

        HashMap<String, List<Vela>> velasPorSimbolo = new HashMap<>();
        for (String symbol : coins) {
            List<Vela> velas = VelaDAO.getInstance()
                    .findBySymbolAndInterval(symbol, timeframe);
            velasPorSimbolo.put(symbol, velas);
        }

        System.out.println("Haciendo backtest:");
        System.out.println("Timeframe: " + timeframe);
        System.out.println("Símbolos: " + String.join(", ", coins));
        ConsoleLoader loader = ConsoleLoader.getInstance();
        loader.startDots();

        BacktestingService backtestingService = new BacktestingService();
        String resultado = backtestingService.ejecutarBacktest(rutaEstrategia, timeframe, velasPorSimbolo);

        SaveFileService saveFileService = new SaveFileService(rutaEstrategia);
        saveFileService.guardarResultados(timeframe, resultado);
        loader.stop();
    }

    public static void comprobarDatosParaEstrategia(String timeframe, List<String> coins) {
        coins.stream().forEach(symbol -> ControladorVela.actualizarDatos(symbol, timeframe));

    }

    public List<String> listarEstrategias() {
        File folder = new File(PathConfig.STRATEGIES_DIR);
        File[] lista = folder.listFiles((dir, name) -> name.endsWith(".py"));

        List<String> nombres = new ArrayList<>();

        if (lista != null && lista.length > 0) {
            for (File f : lista) {
                nombres.add("- " + f.getName().replace(".py", ""));
            }
        } else {
            nombres.add("  (No hay estrategias .py en la carpeta /strategies)");
        }

        return nombres;
    }
}
