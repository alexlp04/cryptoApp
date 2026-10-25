package com.bottrading.trading.infrastructure.persistence;

import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.bottrading.trading.application.port.out.FailedSignalStorePort;
import com.bottrading.trading.domain.SenalFallidaPendiente;
import com.bottrading.trading.domain.SenalFallidaPendienteRepository;
import com.bottrading.trading.infrastructure.bridge.SignalDTO;

import lombok.RequiredArgsConstructor;

/**
 * Adaptador JPA que persiste las señales fallidas pendientes de reintento.
 * Usa soft-delete (marca {@code eliminado}) al procesarlas o descartarlas.
 */
@Component
@RequiredArgsConstructor
public class FailedSignalPersistenceAdapter implements FailedSignalStorePort {

    private final SenalFallidaPendienteRepository repository;

    /** Clave determinista de la señal, independiente del payload persistido. */
    static String claveDe(SignalDTO s) {
        return (s.getSymbol() == null ? "" : s.getSymbol()) + "|"
                + (s.getTimeframe() == null ? "" : s.getTimeframe()) + "|"
                + (s.getAction() == null ? "" : s.getAction()) + "|"
                + s.getTimestamp();
    }

    @Override
    @Transactional
    public void save(Long instanciaId, SignalDTO signal) {
        SenalFallidaPendiente e = new SenalFallidaPendiente();
        e.setInstanciaId(instanciaId);
        e.setClaveSenal(claveDe(signal));
        e.setSymbol(signal.getSymbol());
        e.setAction(signal.getAction());
        e.setPrice(signal.getPrice());
        e.setTimeframe(signal.getTimeframe());
        e.setSignalTimestamp(signal.getTimestamp());
        e.setRealSignal(signal.isReal());
        repository.save(e);
    }

    @Override
    @Transactional
    public void markProcessed(Long instanciaId, SignalDTO signal) {
        repository.findFirstByInstanciaIdAndClaveSenalAndEliminadoFalse(instanciaId, claveDe(signal))
                .ifPresent(e -> {
                    e.setEliminado(true);
                    repository.save(e);
                });
    }

    @Override
    @Transactional
    public void deleteAllForInstance(Long instanciaId) {
        List<SenalFallidaPendiente> pendientes = repository.findByInstanciaIdAndEliminadoFalse(instanciaId);
        pendientes.forEach(e -> e.setEliminado(true));
        repository.saveAll(pendientes);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SignalDTO> loadPending(Long instanciaId) {
        return repository.findByInstanciaIdAndEliminadoFalse(instanciaId).stream()
                .map(FailedSignalPersistenceAdapter::toDto)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Long> instancesWithPending() {
        return repository.findByEliminadoFalse().stream()
                .map(SenalFallidaPendiente::getInstanciaId)
                .distinct()
                .toList();
    }

    private static SignalDTO toDto(SenalFallidaPendiente e) {
        SignalDTO s = new SignalDTO();
        s.setSymbol(e.getSymbol());
        s.setAction(e.getAction());
        s.setPrice(e.getPrice());
        s.setTimeframe(e.getTimeframe());
        s.setTimestamp(e.getSignalTimestamp());
        s.setReal(e.isRealSignal());
        return s;
    }
}
