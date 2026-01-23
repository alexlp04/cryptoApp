package com.bottrading.services;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.bottrading.beans.Vela;
import com.bottrading.repositories.VelaRepository;

import jakarta.transaction.Transactional;

@Service
public class MarketDataService {

    @Autowired
    private VelaRepository velaRepo;

    @Autowired
    private FetchService fetchService;

    @Autowired
    private IndicatorsService indicatorsService;

    @Transactional
    public void actualizarDatosMercado(List<String> symbols, String interval) {
        for (String symbol : symbols) {
            fetchService.fetch(symbol, interval);
        }
    }

    public void calcularIndicadoresParaSimbolo(String symbol, String interval) {
        List<Vela> velas = velaRepo.findBySymbolAndIntervalOrderByOpenTimeAsc(symbol, interval);
        indicatorsService.calculateBasicIndicators(symbol, interval, velas);
    }

}