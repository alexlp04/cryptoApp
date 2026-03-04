package com.bottrading.services;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.bottrading.beans.Vela;
import com.bottrading.repositories.VelaRepository;

import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;

/**
 * Servicio de orquestación de datos de mercado.
 * Actúa como una fachada (Facade) para coordinar la descarga de datos históricos (Velas)
 * y el posterior cálculo de indicadores técnicos.
 */
@Slf4j
@Service
public class MarketDataService {

    private final VelaRepository velaRepo;
    private final FetchService fetchService;
    private final IndicatorsService indicatorsService;

    @Autowired
    public MarketDataService(VelaRepository velaRepo, FetchService fetchService, IndicatorsService indicatorsService) {
        this.velaRepo = velaRepo;
        this.fetchService = fetchService;
        this.indicatorsService = indicatorsService;
    }

    /**
     * Descarga y actualiza las velas (candlesticks) para una lista de símbolos.
     * OPTIMIZACIÓN: Ahora se ejecuta EN PARALELO para símbolos múltiples.
     * Delega la lógica de conexión y persistencia al {@link FetchService}.
     */
    public void actualizarDatosMercado(List<String> symbols, String interval) {
        if (symbols == null || symbols.isEmpty()) {
            log.warn("Lista de símbolos vacía. Abortando");
            return;
        }
        
        log.info("Iniciando descargas paralelas para {} símbolos en {}", symbols.size(), interval);
        
        symbols.parallelStream()
               .forEach(symbol -> {
                   try {
                       fetchService.fetch(symbol, interval);
                   } catch (Exception e) {
                       log.error("Error descargando {}: {}", symbol, e.getMessage());
                   }
               });
        
        log.info("Descargas completadas para todos los símbolos");
    }

    /**
     * Recupera TODOS los datos históricos de la base de datos y lanza el proceso de cálculo de indicadores.
     * NOTA: El recálculo completo de la base de datos se implementará en futuras versiones.
     */
    public void calcularIndicadoresParaSimbolo(String symbol, String interval) {
        log.info("Calculando indicadores técnicos masivos para {} en {}...", symbol, interval);
        
        List<Vela> velas = velaRepo.findBySymbolAndIntervalOrderByOpenTimeAsc(symbol, interval);
        
        if (velas.isEmpty()) {
            log.warn("No hay velas en la base de datos para calcular indicadores.");
            return;
        }
        
        // El cálculo de indicadores se ejecuta inmediatamente después de descargar datos completos
        indicatorsService.calculateBasicIndicators(symbol, velas, true);
    }

    /**
     * Asegura que los datos históricos estén actualizados y los indicadores calculados
     * antes de cedérselos al motor de Inteligencia Artificial (Lógica Incremental).
     *
     * @param symbol   El símbolo del mercado.
     * @param interval El marco temporal.
     * @param dias     El número de días de datos históricos a preparar.
     */
    @Transactional
    public void prepararDatosParaEntrenamiento(String symbol, String interval, int dias, long now) {
        log.info("--- PREPARANDO DATASET PARA IA: {} [{}] ---", symbol, interval);
        
        // 1. FetchService hace la magia de la BD y descarga los datos faltantes
        log.info("Sincronizando Velas (OHLCV)..."); 
        long fetchedFrom = fetchService.fetchIncremental(symbol, interval, dias, now);
        
        // 2. Calculamos los indicadores con margen de seguridad (Warmup)
        // Restamos 50 velas al timestamp para que el RSI y EMA tengan datos para arrancar
        long margenWarmup = 50L * FetchService.getIntervalMillis(interval);
        long calcFromTimestamp = fetchedFrom - margenWarmup;

        // Calculamos si esto ha sido una descarga completa o solo un trozo incremental
        long millisPerDay = 24L * 60L * 60L * 1000L;
        boolean esDescargaCompleta = (fetchedFrom == (now - dias * millisPerDay));

        log.info("Generando variables predictivas desde el anclaje (con warmup)...");
        
        // OPTIMIZACIÓN: Integrar directamente sin método privado intermedio
        List<Vela> velas = velaRepo.findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(symbol, interval, calcFromTimestamp);
        
        if (velas.isEmpty()) {
            log.warn("No hay velas en la base de datos para calcular indicadores.");
            return;
        }
        
        indicatorsService.calculateBasicIndicators(symbol, velas, esDescargaCompleta);
        
        log.info("--- DATASET LISTO ---");
    }

}