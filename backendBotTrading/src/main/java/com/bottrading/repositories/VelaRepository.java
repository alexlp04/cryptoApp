package com.bottrading.repositories;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.bottrading.beans.Vela;

@Repository
public interface VelaRepository extends JpaRepository<Vela, Long> {

    // Buscar velas por símbolo e intervalo ordenadas (findBySymbolAndInterval)
    List<Vela> findBySymbolAndIntervalOrderByOpenTimeAsc(String symbol, String interval);

    // Obtener la última vela (findLastVela)
    Optional<Vela> findFirstBySymbolAndIntervalOrderByOpenTimeDesc(String symbol, String interval);

    // Obtener el último timestamp (getLastTimestamp)
    @Query("SELECT MAX(v.openTime) FROM Vela v WHERE v.symbol = :symbol AND v.interval = :interval")
    Long findMaxOpenTimeBySymbolAndInterval(@Param("symbol") String symbol, @Param("interval") String interval);

    // Verificar si existe una vela (exists)
    boolean existsBySymbolAndIntervalAndOpenTime(String symbol, String interval, Long openTime);
}