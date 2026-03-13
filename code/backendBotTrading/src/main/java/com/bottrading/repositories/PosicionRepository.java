package com.bottrading.repositories;

import com.bottrading.beans.Posicion;
import com.bottrading.beans.InstanciaEstrategia;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface PosicionRepository extends JpaRepository<Posicion, Long> {
    // Busca si hay una posición de BTC abierta para esta estrategia
    Optional<Posicion> findByInstanciaAndSimboloAndAbiertaTrue(InstanciaEstrategia instancia, String simbolo);

    boolean existsByInstanciaAndSimboloAndAbiertaTrue(InstanciaEstrategia instancia, String simbolo);
}