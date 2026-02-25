package com.bottrading.services;

import java.util.List;
import java.util.Optional; // ✅ IMPORT AÑADIDO

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.bottrading.beans.Vela;
import com.bottrading.repositories.IndicadorRepository; // ✅ IMPORT AÑADIDO
import com.bottrading.repositories.VelaRepository;

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
    private final IndicadorRepository indicadorRepo; // ✅ REPOSITORIO AÑADIDO
    private final FetchService fetchService;
    private final IndicatorsService indicatorsService;

    @Autowired
    public MarketDataService(VelaRepository velaRepo, IndicadorRepository indicadorRepo, FetchService fetchService, IndicatorsService indicatorsService) {
        this.velaRepo = velaRepo;
        this.indicadorRepo = indicadorRepo; // ✅ INYECTADO EN EL CONSTRUCTOR
        this.fetchService = fetchService;
        this.indicatorsService = indicatorsService;
    }

    /**
     * Descarga y actualiza las velas (candlesticks) para una lista de símbolos.
     * Delega la lógica de conexión y persistencia al {@link FetchService}.
     *
     * @param symbols  Lista de pares a actualizar (ej: ["BTCUSDT", "ETHUSDT"]).
     * @param interval El intervalo de tiempo (ej: "1h").
     */
    // ❌ @Transactional ELIMINADO: Nunca bloquees la BD mientras esperas a Internet/Python
    public void actualizarDatosMercado(List<String> symbols, String interval) {
        // Iteramos sobre cada símbolo para actualizar sus datos secuencialmente
        for (String symbol : symbols) {
            fetchService.fetch(symbol, interval);
        }
    }

    /**
     * Recupera los datos históricos de la base de datos y lanza el proceso de cálculo de indicadores.
     *
     * @param symbol   El símbolo del mercado.
     * @param interval El intervalo de tiempo.
     */
    public void calcularIndicadoresParaSimbolo(String symbol, String interval) {
        log.info("Calculando indicadores técnicos masivos para {} en {}...", symbol, interval);
        
        List<Vela> velas = velaRepo.findBySymbolAndIntervalOrderByOpenTimeAsc(symbol, interval);
        
        if (velas.isEmpty()) {
            log.warn("No hay velas en la base de datos para calcular indicadores.");
            return;
        }
        
        // Delegar el procesamiento matemático al servicio de indicadores
        indicatorsService.calculateBasicIndicators(symbol, velas);
    }

    /**
     * Asegura que los datos históricos estén actualizados y los indicadores calculados
     * antes de cedérselos al motor de Inteligencia Artificial.
     *
     * @param symbol   El símbolo del mercado.
     * @param interval El marco temporal.
     */
    public void prepararDatosParaEntrenamiento(String symbol, String interval) {
        log.info("--- PREPARANDO DATASET PARA IA: {} [{}] ---", symbol, interval);
        
        // 1. Forzar sincronización con el Exchange (Binance/etc) para tener los datos de hoy
        log.info("Sincronizando Velas (OHLCV)..."); 
        fetchService.fetch(symbol, interval);
        
        // 2. Calcular el RSI, MACD, etc., para que la IA tenga contexto ("Feature Engineering")
        log.info("Generando variables predictivas (Indicadores)...");
        this.calcularIndicadoresParaSimbolo(symbol, interval);
        
        log.info("--- DATASET LISTO ---");
    }

}