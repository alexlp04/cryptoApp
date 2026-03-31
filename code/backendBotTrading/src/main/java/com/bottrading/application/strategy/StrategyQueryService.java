package com.bottrading.application.strategy;

import com.bottrading.domain.strategy.InstanciaEstrategia;
import com.bottrading.domain.strategy.InstanciaEstrategiaRepository;
import com.bottrading.utils.AppConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

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
        return listarPorEstado(AppConstants.KEY_ACTIVA, "No hay estrategias activas.");
    }

    /**
     * Lista estrategias en pausa.
     */
    public List<String> listarEstrategiasDetenidas() {
        return listarPorEstado(AppConstants.KEY_DETENIDA, "No hay estrategias en pausa.");
    }

    /**
     * Lista estrategias terminadas.
     */
    public List<String> listarEstrategiasTerminadas() {
        return listarPorEstado(AppConstants.KEY_TERMINADA, "No hay estrategias terminadas.");
    }


    private List<String> listarPorEstado(String estado, String mensajeVacio) {
        List<InstanciaEstrategia> lista = instanciaRepo.findByEstado(estado);
        if (lista.isEmpty()) {
            return List.of(mensajeVacio);
        }
        return lista.stream()
                .map(InstanciaEstrategia::toString)
                .toList();
    }
}
