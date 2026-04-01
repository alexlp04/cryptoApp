package com.bottrading.infrastructure.bridge;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.bottrading.domain.strategy.InstanciaEstrategia;
import com.bottrading.exceptions.PythonProcessException;
import com.bottrading.utils.PathConfig;
import com.bottrading.utils.PythonProcessSupport;
import com.google.gson.Gson;

import lombok.extern.slf4j.Slf4j;

/**
 * Supervisor de bajo nivel para el ciclo de vida de procesos Python.
 * Gestiona: inicialización, envío de configuración, y destrucción de procesos.
 * 
 * Responsabilidad única: operaciones CRUD sobre procesos del SO.
 */
@Slf4j
@Service
public class RealtimeProcessSupervisor {

    private final PythonBridgeFacade pythonBridgeFacade;
    
    private final Map<Long, Process> activeProcesses = new ConcurrentHashMap<>();

    public RealtimeProcessSupervisor(PythonBridgeFacade pythonBridgeFacade) {
        this.pythonBridgeFacade = pythonBridgeFacade;
    }

    /**
     * Inicia un nuevo proceso Python para la estrategia.
     */
    public Process iniciarProcesoPython(InstanciaEstrategia instancia) throws PythonProcessException {
        String scriptPath = resolverScriptPath(instancia);
        
        log.info("Arrancando proceso Python: {} (ID: {})", instancia.getNombreEstrategia(), instancia.getId());
        
        try {
            Process process = pythonBridgeFacade.startProcess(
                    "trading-rt-" + instancia.getId(),
                    scriptPath,
                    false,
                    Collections.emptyList());
            
            activeProcesses.put(instancia.getId(), process);
            return process;
            
        } catch (PythonBridgeExecutionException e) {
            throw new PythonProcessException("Fallo al iniciar proceso Python: " + e.getMessage(), e);
        }
    }

    /**
     * Destruye un proceso forzosamente y lo desregistra.
     */
    public void destroyProcessForcibly(Long instanciaId) {
        Process process = activeProcesses.remove(instanciaId);
        if (process != null && process.isAlive()) {
            log.warn("Destruyendo proceso Python (ID: {}) forzadamente", instanciaId);
            pythonBridgeFacade.destroyProcess(process);
        }
    }

    /**
     * Destruye un proceso forzosamente.
     */
    public void destroyProcessForcibly(Process process) {
        if (process != null && process.isAlive()) {
            log.warn("Destruyendo proceso Python forzadamente");
            pythonBridgeFacade.destroyProcess(process);
        }
    }

    /**
     * Envía la configuración inicial al proceso Python por STDIN.
     */
    public void enviarPayload(Process process, InstanciaEstrategia instancia, List<String> symbols) 
            throws PythonProcessException {
        Map<String, Object> payload = construirPayload(instancia, symbols);
        
        try (OutputStream os = process.getOutputStream()) {
            PythonProcessSupport.writeUtf8(os, new Gson().toJson(payload));
            log.debug("Payload enviado a proceso: {}", instancia.getId());
        } catch (IOException e) {
            throw new PythonProcessException("Error al enviar configuración a Python: " + e.getMessage(), e);
        }
    }

    /**
     * Limpia la referencia del proceso registrado.
     */
    public void unregisterProcess(Long instanciaId) {
        activeProcesses.remove(instanciaId);
    }

    /**
     * Obtiene el proceso registrado para una estrategia.
     */
    public Process getProcess(Long instanciaId) {
        return activeProcesses.get(instanciaId);
    }

    /**
     * Verifica si hay un proceso activo para la estrategia.
     */
    public boolean hasActiveProcess(Long instanciaId) {
        Process p = activeProcesses.get(instanciaId);
        return p != null && p.isAlive();
    }


    private String resolverScriptPath(InstanciaEstrategia instancia) {
        if (instancia.getNombreModelo() != null && !instancia.getNombreModelo().isEmpty()) {
            return PathConfig.ENGINE_AI_RT_PATH;  // Script para Modelos de ML
        } else {
            return PathConfig.ENGINE_RT_PATH;     // Script estándar
        }
    }

    private Map<String, Object> construirPayload(InstanciaEstrategia instancia, List<String> symbols) {
        Map<String, Object> payload = new HashMap<>();

        if (instancia.getNombreEstrategia() != null && !instancia.getNombreEstrategia().isBlank()) {
            payload.put("strategy_path", PathConfig.getValidStrategyPath(instancia.getNombreEstrategia()));
        }
        payload.put("symbols", new ArrayList<>(symbols));
        payload.put("timeframe", instancia.getTimeframe());
        payload.put("capital", instancia.getCapitalReservado());

        // Agregar modelo si existe (para estrategias de IA)
        if (instancia.getNombreModelo() != null && !instancia.getNombreModelo().isEmpty()) {
            payload.put("model_name", instancia.getNombreModelo());
        }

        return payload;
    }
}
