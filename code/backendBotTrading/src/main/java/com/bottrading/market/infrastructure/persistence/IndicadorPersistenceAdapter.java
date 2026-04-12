package com.bottrading.market.infrastructure.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.bottrading.market.application.port.out.IndicadorRepositoryPort;
import com.bottrading.market.domain.IndicadorRepository;
import com.bottrading.market.domain.IndicadorTecnico;
import com.bottrading.market.domain.Vela;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class IndicadorPersistenceAdapter implements IndicadorRepositoryPort {

    private final IndicadorRepository indicadorRepository;

    @Override
    public List<IndicadorTecnico> findByVela(Vela vela) {
        return indicadorRepository.findByVela(vela);
    }

    @Override
    public List<IndicadorTecnico> findByVelaAndTipo(Vela vela, String tipo) {
        return indicadorRepository.findByVelaAndTipo(vela, tipo);
    }

    @Override
    public Optional<IndicadorTecnico> findByVelaAndTipoAndParametros(Vela vela, String tipo, String parametros) {
        return indicadorRepository.findByVelaAndTipoAndParametros(vela, tipo, parametros);
    }

    @Override
    public boolean existsByVelaAndTipoAndParametros(Vela vela, String tipo, String parametros) {
        return indicadorRepository.existsByVelaAndTipoAndParametros(vela, tipo, parametros);
    }

    @Override
    public boolean existsByVelaId(Long velaId) {
        return indicadorRepository.existsByVela_Id(velaId);
    }

    @Override
    public void deleteByVela(Vela vela) {
        indicadorRepository.deleteByVela(vela);
    }

    @Override
    public void deleteByVelaSymbolAndIntervalAndOpenTimeGreaterThanEqual(String symbol, String interval,
            Long openTime) {
        indicadorRepository.deleteByVelaSymbolAndIntervalAndOpenTimeGreaterThanEqual(symbol, interval, openTime);
    }

    @Override
    public List<IndicadorTecnico> findByVelaIn(List<Vela> velas) {
        return indicadorRepository.findByVelaIn(velas);
    }

    @Override
    public <S extends IndicadorTecnico> S save(S indicador) {
        return indicadorRepository.save(indicador);
    }
}
