package com.bottrading.trading.domain;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SenalFallidaPendienteRepository extends JpaRepository<SenalFallidaPendiente, Long> {

    List<SenalFallidaPendiente> findByInstanciaIdAndEliminadoFalse(Long instanciaId);

    List<SenalFallidaPendiente> findByEliminadoFalse();

    Optional<SenalFallidaPendiente> findFirstByInstanciaIdAndClaveSenalAndEliminadoFalse(
            Long instanciaId, String claveSenal);
}
