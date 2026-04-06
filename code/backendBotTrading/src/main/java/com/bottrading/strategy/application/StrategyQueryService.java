package com.bottrading.strategy.application;

import java.util.List;

import org.springframework.stereotype.Service;

import com.bottrading.strategy.domain.EstadoEstrategia;
import com.bottrading.strategy.domain.InstanciaEstrategia;
import com.bottrading.strategy.domain.InstanciaEstrategiaRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Servicio de consulta para estrategias.
 * Lectura pura: listados, filtrado por estado, consultas de capital.
 * 
 * Responsabilidad única: operaciones de lectura y consulta.
 */
@Slf4j
@Service
public class StrategyQueryService {

    private final InstanciaEstrategiaRepository instanciaRepo;

    public StrategyQueryService(InstanciaEstrategiaRepository instanciaRepo) {
        this.instanciaRepo = instanciaRepo;
    }

    /**
     * Lista todas las estrategias.
     */
    public List<String> listarEstrategias() {
        return instanciaRepo.findAll()
                .stream()
                .map(InstanciaEstrategia::toString)
                .toList();
    }

    /**
     * Lista estrategias activas.
     */
    public List<String> listarEstrategiasActivas() {
        return listarPorEstado(EstadoEstrategia.ACTIVA, "No hay estrategias activas.");
    }

    /**
     * Lista estrategias en pausa.
     */
    public List<String> listarEstrategiasDetenidas() {
        return listarPorEstado(EstadoEstrategia.DETENIDA, "No hay estrategias en pausa.");
    }

    /**
     * Lista estrategias terminadas.
     */
    public List<String> listarEstrategiasTerminadas() {
        return listarPorEstado(EstadoEstrategia.TERMINADA, "No hay estrategias terminadas.");
    }


    private List<String> listarPorEstado(EstadoEstrategia estado, String mensajeVacio) {
        List<InstanciaEstrategia> lista = instanciaRepo.findByEstado(estado);
        if (lista.isEmpty()) {
            return List.of(mensajeVacio);
        }
        return lista.stream()
                .map(InstanciaEstrategia::toString)
                .toList();
    }
}
