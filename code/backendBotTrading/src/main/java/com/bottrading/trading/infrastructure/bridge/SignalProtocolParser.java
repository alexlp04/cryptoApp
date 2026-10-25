package com.bottrading.trading.infrastructure.bridge;

import org.springframework.stereotype.Service;

import com.bottrading.trading.infrastructure.bridge.SignalDTO;
import com.bottrading.shared.exceptions.SignalProcessingException;
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
    private static final String HEARTBEAT_PREFIX = "HEARTBEAT";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Procesa una línea del stdout de Python.
     * Retorna un SignalDTO si es una señal válida, o null si es un log/heartbeat.
     */
    public SignalDTO parseLineaLog(String line, Long instanciaId) {
        String trimmedLine = line.trim();

        if (trimmedLine.startsWith(SIGNAL_PREFIX)) {
            return parseSignal(trimmedLine.substring(SIGNAL_PREFIX.length()), instanciaId);
        } else if (isHeartbeat(trimmedLine)) {
            log.trace("PYBEAT [{}]", instanciaId);
            return null;
        } else {
            log.info("PYLOG [{}]: {}", instanciaId, line);
            return null;
        }
    }

    /**
     * Indica si una línea es un heartbeat de liveness del motor Python.
     */
    public boolean isHeartbeat(String line) {
        return line != null && line.trim().startsWith(HEARTBEAT_PREFIX);
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
        } catch (SignalProcessingException e) {
            // Misma política que el JSON roto: una línea mala se descarta, no mata el motor entero.
            log.warn("Señal inválida de [{}] descartada: {} - {}", instanciaId, e.getMessage(), jsonLine);
            return null;
        }
    }

    /**
     * Valida los campos que el motor de trading necesita y normaliza la acción a mayúsculas.
     * Un precio nulo o 0 dejaba posiciones imposibles de cerrar (división por cero).
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
        String action = signal.getAction().toUpperCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("BUY", "SELL", "SHORT", "COVER").contains(action)) {
            throw new SignalProcessingException("SignalDTO: action desconocida '" + signal.getAction() + "'");
        }
        signal.setAction(action);
        if (signal.getPrice() == null || signal.getPrice().signum() <= 0) {
            throw new SignalProcessingException("SignalDTO: price nulo o no positivo");
        }
        if (signal.getTimestamp() <= 0) {
            throw new SignalProcessingException("SignalDTO: timestamp no positivo");
        }
    }
}
