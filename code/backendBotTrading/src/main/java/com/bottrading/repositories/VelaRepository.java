package com.bottrading.repositories;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.bottrading.beans.Vela;

@Repository
public interface VelaRepository extends JpaRepository<Vela, Long> {

    @Query("SELECT v FROM Vela v WHERE v.symbol = :symbol AND v.interval = :interval ORDER BY v.openTime ASC")
    List<Vela> findBySymbolAndIntervalOrderByOpenTimeAsc(
            @Param("symbol") String symbol,
            @Param("interval") String interval);

    // Obtener la última vela (findLastVela)
    Optional<Vela> findFirstBySymbolAndIntervalOrderByOpenTimeDesc(String symbol, String interval);

    // Obtener el último timestamp (getLastTimestamp)
    @Query("SELECT MAX(v.openTime) FROM Vela v WHERE v.symbol = :symbol AND v.interval = :interval")
    Long findMaxOpenTimeBySymbolAndInterval(@Param("symbol") String symbol, @Param("interval") String interval);

        @Query("SELECT MIN(v.openTime) FROM Vela v WHERE v.symbol = :symbol AND v.interval = :interval")
        Long findMinOpenTimeBySymbolAndInterval(@Param("symbol") String symbol, @Param("interval") String interval);

    // Verificar si existe una vela (exists)
    boolean existsBySymbolAndIntervalAndOpenTime(String symbol, String interval, Long openTime);

    @Modifying
    @Transactional
    @Query("DELETE FROM Vela v WHERE v.symbol = :symbol AND v.interval = :interval AND v.openTime >= :openTime")
    void deleteBySymbolAndIntervalAndOpenTimeGreaterThanEqual(
            @Param("symbol") String symbol,
            @Param("interval") String interval,
            @Param("openTime") Long openTime);


    @Query("SELECT v FROM Vela v WHERE v.symbol = :symbol AND v.interval = :interval AND v.openTime >= :openTime ORDER BY v.openTime ASC")
    List<Vela> findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(
            @Param("symbol") String symbol, 
            @Param("interval") String interval, 
            @Param("openTime") Long openTime
    );

    /**
     * Cuenta velas en un rango de openTime para detección de huecos de integridad.
     */
    @Query("SELECT COUNT(v) FROM Vela v WHERE v.symbol = :symbol AND v.interval = :interval AND v.openTime BETWEEN :from AND :to")
    long countBySymbolAndIntervalAndOpenTimeBetween(
            @Param("symbol") String symbol,
            @Param("interval") String interval,
            @Param("from") Long from,
            @Param("to") Long to);

    @Query("SELECT MIN(v.openTime) FROM Vela v WHERE v.symbol = :symbol AND v.interval = :interval AND v.openTime BETWEEN :from AND :to")
    Long findMinOpenTimeBySymbolAndIntervalAndOpenTimeBetween(
            @Param("symbol") String symbol,
            @Param("interval") String interval,
            @Param("from") Long from,
            @Param("to") Long to);

    @Query("SELECT MAX(v.openTime) FROM Vela v WHERE v.symbol = :symbol AND v.interval = :interval AND v.openTime BETWEEN :from AND :to")
    Long findMaxOpenTimeBySymbolAndIntervalAndOpenTimeBetween(
            @Param("symbol") String symbol,
            @Param("interval") String interval,
            @Param("from") Long from,
            @Param("to") Long to);

    /**
     * Devuelve el primer timestamp faltante interno en el rango, calculado como (open_time + step)
     * cuando no existe la vela siguiente esperada.
     */
    @Query(value = """
            SELECT MIN(v.open_time + :step)
            FROM vela v
            LEFT JOIN vela n
              ON n.symbol = v.symbol
             AND n.time_interval = v.time_interval
             AND n.open_time = v.open_time + :step
            WHERE v.symbol = :symbol
              AND v.time_interval = :interval
              AND v.open_time BETWEEN :from AND :to
              AND v.open_time + :step <= :to
              AND n.id IS NULL
            """, nativeQuery = true)
    Long findFirstInternalGapOpenTime(
            @Param("symbol") String symbol,
            @Param("interval") String interval,
            @Param("from") Long from,
            @Param("to") Long to,
            @Param("step") Long step);

    
}