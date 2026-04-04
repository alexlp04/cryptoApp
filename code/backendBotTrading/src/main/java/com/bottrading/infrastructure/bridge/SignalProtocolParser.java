package com.bottrading.infrastructure.bridge;

import org.springframework.stereotype.Service;

import com.bottrading.beans.SignalDTO;
import com.bottrading.exceptions.SignalProcessingException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * Parser de protocolo IPC para líneas de entrada desde procesos Python.
 * Detecta y valida el formato de líneas (logs, señales JSON, etc.).
 * 
 * Responsabilidad única: parsing y validación de formato de mensajes.
 */
@Slf4j
@Service
public class SignalProtocolParser {

    private static final String SIGNAL_PREFIX = "SIGNAL\t";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Procesa una línea del stdout de Python.
     * Retorna un SignalDTO si es una señal válida, o null si es un log normal.
     */
    public SignalDTO parseLineaLog(String line, Long instanciaId) {
        String trimmedLine = line.trim();

        if (trimmedLine.startsWith(SIGNAL_PREFIX)) {
            return parseSignal(trimmedLine.substring(SIGNAL_PREFIX.length()), instanciaId);
        } else {
            log.info("PYLOG [{}]: {}", instanciaId, line);
            return null;
        }
    }

    /**
     * Parsea una línea JSON como SignalDTO.
     * Lanza excepciones en caso de JSON inválido.
     */
    private SignalDTO parseSignal(String jsonLine, Long instanciaId) {
        try {
            SignalDTO signal = MAPPER.readValue(jsonLine, SignalDTO.class);
            validarSignal(signal);
            log.debug("Señal parseada correctamente: [{}] {} {}", 
                     instanciaId, signal.getAction(), signal.getSymbol());
            return signal;
            
        } catch (JsonProcessingException e) {
            log.error("JSON corrupto de Python (será ignorado): {} - Error: {}", jsonLine, e.getMessage());
            // No lanzar excepción, simplemente ignorar líneas malformadas
            return null;
        }
    }

    /**
     * Valida que una señal tenga los campos mínimos requeridos.
     */
    private void validarSignal(SignalDTO signal) throws SignalProcessingException {
        if (signal == null) {
            throw new SignalProcessingException("SignalDTO es null");
        }
        if (signal.getSymbol() == null || signal.getSymbol().isEmpty()) {
            throw new SignalProcessingException("SignalDTO: falta symbol");
        }
        if (signal.getAction() == null || signal.getAction().isEmpty()) {
            throw new SignalProcessingException("SignalDTO: falta action");
        }
    }
}
