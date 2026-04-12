package com.bottrading.market.domain;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.transaction.Transactional;

@Repository
public interface IndicadorRepository extends JpaRepository<IndicadorTecnico, Long> {

    List<IndicadorTecnico> findByVela(Vela vela);

    List<IndicadorTecnico> findByVelaAndTipo(Vela vela, String tipo);

    Optional<IndicadorTecnico> findByVelaAndTipoAndParametros(Vela vela, String tipo, String parametros);

    boolean existsByVelaAndTipoAndParametros(Vela vela, String tipo, String parametros);

    boolean existsByVela_Id(Long velaId);

    @Modifying
    @Transactional
    @Query("DELETE FROM IndicadorTecnico i WHERE i.vela = :vela")
    void deleteByVela(@Param("vela") Vela vela);

    @Modifying
    @Transactional
    @Query("DELETE FROM IndicadorTecnico i WHERE i.vela.id IN (SELECT v.id FROM Vela v WHERE v.symbol = :symbol AND v.interval = :interval AND v.openTime >= :openTime)")
    void deleteByVelaSymbolAndIntervalAndOpenTimeGreaterThanEqual(
            @Param("symbol") String symbol,
            @Param("interval") String interval,
            @Param("openTime") Long openTime);

    List<IndicadorTecnico> findByVelaIn(List<Vela> velas);
}