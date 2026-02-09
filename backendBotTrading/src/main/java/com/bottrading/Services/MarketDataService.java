package com.bottrading.services;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.bottrading.beans.Vela;
import com.bottrading.repositories.VelaRepository;

import jakarta.transaction.Transactional;

/**
 * Servicio de orquestación de datos de mercado.
 * Actúa como una fachada (Facade) para coordinar la descarga de datos históricos (Velas)
 * y el posterior cálculo de indicadores técnicos.
 */
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
     * Delega la lógica de conexión y persistencia al {@link FetchService}.
     *
     * @param symbols  Lista de pares a actualizar (ej: ["BTCUSDT", "ETHUSDT"]).
     * @param interval El intervalo de tiempo (ej: "1h").
     */
    @Transactional
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
        // 1. Obtener toda la historia disponible para maximizar la precisión de los indicadores (ej: medias móviles)
        List<Vela> velas = velaRepo.findBySymbolAndIntervalOrderByOpenTimeAsc(symbol, interval);
        
        // 2. Delegar el procesamiento matemático al servicio de indicadores
        indicatorsService.calculateBasicIndicators(symbol, interval, velas);
    }

}