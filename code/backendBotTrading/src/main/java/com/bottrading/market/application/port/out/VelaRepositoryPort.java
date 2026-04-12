package com.bottrading.market.application.port.out;

import java.util.List;
import java.util.Optional;

import com.bottrading.market.domain.Vela;

/**
 * Puerto de salida para persistencia de velas de mercado.
 */
public interface VelaRepositoryPort {

    List<Vela> findBySymbolAndIntervalOrderByOpenTimeAsc(String symbol, String interval);

    List<Vela> findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(
            String symbol,
            String interval,
            Long openTime);

    Optional<Vela> findFirstBySymbolAndIntervalOrderByOpenTimeDesc(String symbol, String interval);

    Long findMaxOpenTimeBySymbolAndInterval(String symbol, String interval);

    Long findMinOpenTimeBySymbolAndInterval(String symbol, String interval);

    long countBySymbolAndIntervalAndOpenTimeBetween(String symbol, String interval, Long from, Long to);

    Long findMinOpenTimeBySymbolAndIntervalAndOpenTimeBetween(String symbol, String interval, Long from, Long to);

    Long findMaxOpenTimeBySymbolAndIntervalAndOpenTimeBetween(String symbol, String interval, Long from, Long to);

    Long findFirstInternalGapOpenTime(String symbol, String interval, Long from, Long to, Long step);

    void deleteBySymbolAndIntervalAndOpenTimeGreaterThanEqual(String symbol, String interval, Long openTime);

    <S extends Vela> S save(S vela);
}
