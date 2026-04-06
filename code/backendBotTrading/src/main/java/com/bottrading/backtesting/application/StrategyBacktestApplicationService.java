package com.bottrading.backtesting.application;

import java.io.File;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.bottrading.backtesting.infrastructure.FileService;
import com.bottrading.market.domain.Vela;
import com.bottrading.market.domain.VelaRepository;
import com.bottrading.shared.utils.ConsoleLoader;
import com.bottrading.shared.utils.PathConfig;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class StrategyBacktestApplicationService {

    private final VelaRepository velaRepository;
    private final BacktestingService backtestingService;
    private final FileService fileService;

    public StrategyBacktestApplicationService(
            VelaRepository velaRepository,
            BacktestingService backtestingService,
            FileService fileService) {
        this.velaRepository = velaRepository;
        this.backtestingService = backtestingService;
        this.fileService = fileService;
    }

    public void ejecutarBacktest(String nombreEstra, String tf, List<String> coins,
            BigDecimal capitalAsignado, BigDecimal risk, boolean limpiarBacktestsPrevios,
            boolean guardarTrades) throws Exception {
        
        fileService.verificarYLimpiarCarpetaEstrategia(nombreEstra, limpiarBacktestsPrevios);

        ConsoleLoader.getInstance().startDots("Preparando datos para backtest");
        Map<String, List<Vela>> velasPorSimbolo = cargarDatosHistoricos(coins, tf);

        if (velasPorSimbolo.isEmpty()) {
            log.error("Abortado: No hay datos históricos para procesar.");
            return;
        }

        String strategyPath = PathConfig.getValidStrategyPath(nombreEstra);
        ConsoleLoader.getInstance().stopClear();
        
        String jsonResultado = backtestingService.ejecutarBacktest(
            strategyPath,
            nombreEstra,
            tf,
            velasPorSimbolo,
            capitalAsignado,
            risk,
            guardarTrades);

        if (jsonResultado != null && !jsonResultado.isEmpty()) {
            fileService.guardarEstadisticasDelBacktest(nombreEstra, tf, jsonResultado);
            ConsoleLoader.getInstance().stopClear();
            log.info("Resultados guardados en: {}{}{}", PathConfig.RESULTS_DIR, File.separator, nombreEstra);
        } else {
            log.error("El motor de backtesting no retornó resultados.");
        }
    }


    private Map<String, List<Vela>> cargarDatosHistoricos(List<String> coins, String tf) {
        Map<String, List<Vela>> velasPorSimbolo = new HashMap<>();

        for (String symbol : coins) {
            List<Vela> velas = velaRepository.findBySymbolAndIntervalOrderByOpenTimeAsc(symbol, tf);
            velasPorSimbolo.put(symbol, velas);
            
            if (velas.isEmpty()) {
                log.warn("Aviso: No se encontraron velas para {} / {}", symbol, tf);
            } else {
                log.debug("Cargadas {} velas para {}/{}", velas.size(), symbol, tf);
            }
        }

        return velasPorSimbolo;
    }
}
