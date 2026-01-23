package com.bottrading.services;

import com.bottrading.beans.InstanciaEstrategia;
import com.bottrading.beans.Vela;
import com.bottrading.repositories.InstanciaEstrategiaRepository;
import com.bottrading.repositories.VelaRepository;
import com.bottrading.utils.PathConfig;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.math.BigDecimal;
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

    @Transactional
    public void iniciarTradeRT(String nombreEstra, String tf, List<String> coins,
            boolean isReal, Long walletId, BigDecimal risk, BigDecimal capital) {
        String strategyPath;
        try {
            strategyPath = PathConfig.getValidStrategyPath(nombreEstra);
        } catch (Exception e) {
            throw new RuntimeException(
                    "La estrategia '" + nombreEstra + "' no existe en el directorio de estrategias.");
        }
        // 1. Persistir la instancia (Usamos el repo de Spring)
        InstanciaEstrategia instancia = new InstanciaEstrategia();
        instancia.setNombreEstrategia(strategyPath);
        instancia.setTimeframe(tf);
        instancia.setCapitalAsignado(capital);
        instancia.setRiskPerTrade(risk);
        instancia.setSimbolos(coins);
        instancia.setEsReal(isReal);
        instancia.setEstado("CREADA"); // Estado inicial

        instancia = instanciaRepo.save(instancia);

        // 2. Activar contablemente (Mueve el dinero a RESERVED)
        accountingService.activateStrategy(walletId, instancia.getId(), capital);

        // 3. Delegar la ejecución técnica al TradingService
        tradingService.ejecutarTradeEnTiempoReal(instancia, coins);
    }

    public BigDecimal getCapitalComprometido(String nombreWallet) {
        Double sum = instanciaRepo.sumCapitalActivoByWallet(nombreWallet);
        return sum != null ? BigDecimal.valueOf(sum) : BigDecimal.ZERO;
    }

    public void ejecutarBacktest(String nombreEstra, String tf, List<String> coins) throws Exception {
        Map<String, List<Vela>> velasPorSimbolo = new HashMap<>();
        for (String symbol : coins) {
            velasPorSimbolo.put(symbol, velaRepo.findBySymbolAndIntervalOrderByOpenTimeAsc(symbol, tf));
        }
        backtestingService.ejecutarBacktest(nombreEstra, tf, velasPorSimbolo);
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