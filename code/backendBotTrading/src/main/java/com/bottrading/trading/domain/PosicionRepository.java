package com.bottrading.trading.domain;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.bottrading.strategy.domain.InstanciaEstrategia;

@Repository
public interface PosicionRepository extends JpaRepository<Posicion, Long> {

    Optional<Posicion> findByInstanciaAndSimboloAndAbiertaTrue(InstanciaEstrategia instancia, String simbolo);

    boolean existsByInstanciaAndSimboloAndAbiertaTrue(InstanciaEstrategia instancia, String simbolo);

    List<Posicion> findByInstanciaAndAbiertaTrue(InstanciaEstrategia instancia);

    /** Posiciones de una estrategia, abiertas o cerradas, de la más reciente a la más antigua. */
    List<Posicion> findByInstanciaIdAndAbiertaOrderByIdDesc(Long instanciaId, boolean abierta,
            org.springframework.data.domain.Pageable page);

    long countByInstanciaIdAndAbiertaTrue(Long instanciaId);

    /** Operaciones cerradas en orden cronológico, para la curva de resultados. */
    List<Posicion> findByInstanciaIdAndAbiertaFalseOrderByFechaCierreAsc(Long instanciaId);
}
