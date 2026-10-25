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

import com.bottrading.market.application.port.in.FetchMarketDataUseCase;
import com.bottrading.market.application.port.in.MarketDataUseCase;
import com.bottrading.market.application.port.out.VelaRepositoryPort;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * Servicio de orquestación de datos de mercado.
 * Coordina la descarga de datos de mercado de varios símbolos en paralelo.
 */
@Slf4j
@Service
public class MarketOrchestrationService implements MarketDataUseCase {

    private final VelaRepositoryPort velaRepo;
    private final FetchMarketDataUseCase fetchService;
    private final ExecutorService marketDataExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public MarketOrchestrationService(VelaRepositoryPort velaRepo,
            FetchMarketDataUseCase fetchService) {
        this.velaRepo = velaRepo;
        this.fetchService = fetchService;
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