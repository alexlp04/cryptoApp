package com.bottrading.services;

import com.bottrading.beans.InstanciaEstrategia;
import com.bottrading.beans.Vela;
import com.bottrading.repositories.InstanciaEstrategiaRepository;
import com.bottrading.repositories.VelaRepository;
import com.bottrading.utils.ConsoleLoader;
import com.bottrading.utils.PathConfig;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.Console;
import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class EstrategiaService {

    @Autowired
    private InstanciaEstrategiaRepository instanciaRepo;

    @Autowired
    private VelaRepository velaRepo;

    @Autowired
    private AccountingService accountingService;

    @Autowired
    private TradingService tradingService;

    @Autowired
    private BacktestingService backtestingService;

    @Autowired
    private FileService fileService;

    @Transactional
    public void iniciarTradeRT(String nombreEstra, String tf, List<String> coins,
            boolean isReal, Long walletId, BigDecimal risk, BigDecimal capital) {

        // 1. Persistir la instancia (Usamos el repo de Spring)
        InstanciaEstrategia instancia = new InstanciaEstrategia();
        instancia.setNombreEstrategia(nombreEstra);
        instancia.setTimeframe(tf);
        instancia.setCapitalAsignado(capital);
        instancia.setRiskPerTrade(risk);
        instancia.setSimbolos(new ArrayList<>(coins));
        instancia.setEsReal(isReal);
        instancia.setWalletAsociada(null);
        instancia.setCapitalReservado(capital);
        instancia.setCapitalComprometido(BigDecimal.ZERO);
        instancia.setRiesgoAbierto(BigDecimal.ZERO);
        instancia.setEliminado(false);
        instancia.setEstado("CREADA"); // Estado inicial
 
        instancia = instanciaRepo.save(instancia);

        // 2. Activar contablemente (Mueve el dinero a RESERVED)
        instancia = accountingService.activateStrategy(walletId, instancia.getId(), capital);

        
        // 3. Delegar la ejecución técnica al TradingService
        tradingService.ejecutarTradeEnTiempoReal(instancia, coins);
    }

    public BigDecimal getCapitalComprometido(Long walletAsociada) {
        Double sum = instanciaRepo.sumCapitalActivoByWallet(walletAsociada);
        return sum != null ? BigDecimal.valueOf(sum) : BigDecimal.ZERO;
    }

    public void ejecutarBacktest(String nombreEstra, String tf, List<String> coins) throws Exception {

        fileService.verificarYLimpiarCarpetaEstrategia(nombreEstra);
        ConsoleLoader.getInstance().startDots();
        Map<String, List<Vela>> velasPorSimbolo = new HashMap<>();
        for (String symbol : coins) {
            velasPorSimbolo.put(symbol, velaRepo.findBySymbolAndIntervalOrderByOpenTimeAsc(symbol, tf));
            if (velasPorSimbolo.get(symbol).isEmpty())
                System.err.println("No hay velas para " + symbol + " en " + tf + ". Saltando.");
        }

        if (velasPorSimbolo.isEmpty()) {
            System.err.println("No hay datos para procesar ningún símbolo. Abortando backtest.");
            return;

        }
        String strategyPath = PathConfig.getValidStrategyPath(nombreEstra);

        String jsonResultado = backtestingService.ejecutarBacktest(strategyPath, tf, velasPorSimbolo);
        ConsoleLoader.getInstance().stop();
        if (jsonResultado != null && !jsonResultado.isEmpty()) {
            fileService.guardarResultadosCompletos(nombreEstra, tf, jsonResultado);
            System.out.println("Backtest finalizado. Resultados guardados en: " + PathConfig.RESULTS_DIR
                    + File.separator + nombreEstra);
        } else {
            System.err.println("El motor de backtest no devolvió resultados.");
        }
    }

    public List<String> listarEstrategias() {
        File folder = new File(PathConfig.STRATEGIES_DIR);
        File[] files = folder.listFiles((dir, name) -> name.endsWith(".py"));

        if (files == null || files.length == 0) {
            return List.of("No se encontraron estrategias en: " + PathConfig.STRATEGIES_DIR);
        }

        return Arrays.stream(files)
                .map(f -> "- " + f.getName().replace(".py", ""))
                .collect(Collectors.toList());
    }

}