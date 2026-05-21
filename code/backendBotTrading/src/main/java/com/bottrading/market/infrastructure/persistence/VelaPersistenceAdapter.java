package com.bottrading.market.infrastructure.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.bottrading.market.application.port.out.VelaRepositoryPort;
import com.bottrading.market.domain.Vela;
import com.bottrading.market.domain.VelaRepository;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class VelaPersistenceAdapter implements VelaRepositoryPort {

    private final VelaRepository velaRepository;

    @Override
    public List<Vela> findBySymbolAndIntervalOrderByOpenTimeAsc(String symbol, String interval) {
        return velaRepository.findBySymbolAndIntervalOrderByOpenTimeAsc(symbol, interval);
    }

    @Override
    public List<Vela> findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(
            String symbol,
            String interval,
            Long openTime) {
        return velaRepository.findBySymbolAndIntervalAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(symbol, interval,
                openTime);
    }

    @Override
    public Optional<Vela> findFirstBySymbolAndIntervalOrderByOpenTimeDesc(String symbol, String interval) {
        return velaRepository.findFirstBySymbolAndIntervalOrderByOpenTimeDesc(symbol, interval);
    }

    @Override
    public Long findMaxOpenTimeBySymbolAndInterval(String symbol, String interval) {
        return velaRepository.findMaxOpenTimeBySymbolAndInterval(symbol, interval);
    }

    @Override
    public Long findMinOpenTimeBySymbolAndInterval(String symbol, String interval) {
        return velaRepository.findMinOpenTimeBySymbolAndInterval(symbol, interval);
    }

    @Override
    public long countBySymbolAndIntervalAndOpenTimeBetween(String symbol, String interval, Long from, Long to) {
        return velaRepository.countBySymbolAndIntervalAndOpenTimeBetween(symbol, interval, from, to);
    }

    @Override
    public Long findMinOpenTimeBySymbolAndIntervalAndOpenTimeBetween(String symbol, String interval, Long from,
            Long to) {
        return velaRepository.findMinOpenTimeBySymbolAndIntervalAndOpenTimeBetween(symbol, interval, from, to);
    }

    @Override
    public Long findMaxOpenTimeBySymbolAndIntervalAndOpenTimeBetween(String symbol, String interval, Long from,
            Long to) {
        return velaRepository.findMaxOpenTimeBySymbolAndIntervalAndOpenTimeBetween(symbol, interval, from, to);
    }

    @Override
    public Long findFirstInternalGapOpenTime(String symbol, String interval, Long from, Long to, Long step) {
        return velaRepository.findFirstInternalGapOpenTime(symbol, interval, from, to, step);
    }

    @Override
    public void deleteBySymbolAndIntervalAndOpenTimeGreaterThanEqual(String symbol, String interval, Long openTime) {
        velaRepository.deleteBySymbolAndIntervalAndOpenTimeGreaterThanEqual(symbol, interval, openTime);
    }

    @Override
    public <S extends Vela> S save(S vela) {
        return velaRepository.save(vela);
    }
}
