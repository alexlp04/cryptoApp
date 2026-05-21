package com.bottrading.market.application;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;

import com.bottrading.market.application.port.in.CalculateIndicatorsUseCase;
import com.bottrading.market.application.port.in.FetchMarketDataUseCase;
import com.bottrading.market.application.port.in.MarketDataUseCase;
import com.bottrading.market.application.port.out.VelaRepositoryPort;
import com.bottrading.market.domain.Vela;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * Servicio de orquestación de datos de mercado.
 * Coordina descarga histórica, recálculo de indicadores y preparación de datasets.
 */
@Slf4j
@Service
public class MarketOrchestrationService implements MarketDataUseCase {

    private final VelaRepositoryPort velaRepo;
    private final FetchMarketDataUseCase fetchService;
    private final CalculateIndicatorsUseCase indicatorsService;
    private final ExecutorService marketDataExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public MarketOrchestrationService(VelaRepositoryPort velaRepo,
            FetchMarketDataUseCase fetchService,
            CalculateIndicatorsUseCase indicatorsService) {
        this.velaRepo = velaRepo;
        this.fetchService = fetchService;
        this.indicatorsService = indicatorsService;
    }

    @Override
    public void actualizarDatosMercado(List<String> symbols, String interval) {
        if (symbols == null || symbols.isEmpty()) {
            log.warn("Lista de símbolos vacía. Abortando");
            return;
        }

        log.info("Iniciando descargas paralelas para {} símbolos en {}", symbols.size(), interval);

        List<Callable<String>> tareas = new ArrayList<>(symbols.size());
        for (String symbol : symbols) {
            Callable<String> tarea = () -> {
                fetchService.fetch(symbol, interval);
                return symbol;
            };
            tareas.add(tarea);
        }

        try {
            List<Future<String>> resultados = marketDataExecutor.invokeAll(tareas);
            verificarResultadosActualizacion(resultados);
            log.info("Descargas completadas para todos los símbolos");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Proceso de actualización de mercado interrumpido. Tareas pendientes canceladas.", e);
        }
    }

    private void verificarResultadosActualizacion(List<Future<String>> resultados) {
        for (Future<String> resultado : resultados) {
            try {
                resultado.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Verificación de descarga interrumpida.", e);
            } catch (ExecutionException e) {
                log.error("Fallo en una descarga de mercado: {}", e.getMessage(), e);
            }
        }
    }

    @Override
    public void calcularIndicadoresParaSimbolo(String symbol, String interval) {
        log.info("Calculando indicadores técnicos masivos para {} en {}...", symbol, interval);

        List<Vela> velas = velaRepo.findBySymbolAndIntervalOrderByOpenTimeAsc(symbol, interval);

        if (velas.isEmpty()) {
            log.warn("No hay velas en la base de datos para calcular indicadores.");
            return;
        }

        indicatorsService.calculateBasicIndicators(symbol, velas, true);
    }

    @Override
    public void prepararDatosParaEntrenamiento(String symbol, String interval, int dias, long now) {
        log.info("--- PREPARANDO DATASET PARA IA: {} [{}] ---", symbol, interval);

        log.info("Sincronizando Velas (OHLCV)...");
        long fetchedFrom = fetchService.fetchIncremental(symbol, interval, dias, now);

        long margenWarmup = 50L * FetchService.getIntervalMillis(interval);
        long calcFromTimestamp = fetchedFrom - margenWarmup;

        long millisPerDay = 24L * 60L * 60L * 1000L;
        boolean esDescargaCompleta = fetchedFrom == (now - dias * millisPerDay);

        log.info("Generando variables predictivas desde el anclaje (con warmup)...");

        List<Vela> velas = velaRepo.findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(symbol,
                interval, calcFromTimestamp);

        if (velas.isEmpty()) {
            log.warn("No hay velas en la base de datos para calcular indicadores.");
            return;
        }

        indicatorsService.calculateBasicIndicators(symbol, velas, esDescargaCompleta);

        log.info("--- DATASET LISTO ---");
    }

    @PreDestroy
    public void shutdown() {
        marketDataExecutor.shutdown();
        try {
            if (!marketDataExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                marketDataExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            marketDataExecutor.shutdownNow();
        }
    }
}