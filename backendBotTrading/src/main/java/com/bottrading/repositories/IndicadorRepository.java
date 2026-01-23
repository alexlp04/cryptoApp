package com.bottrading.repositories;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.bottrading.beans.IndicadorTecnico;
import com.bottrading.beans.Vela;

import jakarta.transaction.Transactional;

@Repository
public interface IndicadorRepository extends JpaRepository<IndicadorTecnico, Long> {

    // Buscar indicadores por vela (findByVela)
    List<IndicadorTecnico> findByVela(Vela vela);

    // Buscar por vela y tipo (findByVelaAndTipo)
    List<IndicadorTecnico> findByVelaAndTipo(Vela vela, String tipo);

    // Buscar específico (findByVelaTipoParametros)
    Optional<IndicadorTecnico> findByVelaAndTipoAndParametros(Vela vela, String tipo, String parametros);

    // Verificar existencia (exists)
    boolean existsByVelaAndTipoAndParametros(Vela vela, String tipo, String parametros);

    // Eliminar indicadores de una vela (deleteByVela)
    @Modifying
    @Transactional
    @Query("DELETE FROM IndicadorTecnico i WHERE i.vela = :vela")
    void deleteByVela(@Param("vela") Vela vela);
}