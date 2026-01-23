package com.bottrading.controllers;

import java.util.List;
import com.bottrading.Services.IndicatorsService;
import com.bottrading.beans.IndicadorTecnico;
import com.bottrading.beans.IndicadorTecnicoDTO;
import com.bottrading.beans.Vela;
import com.bottrading.daos.IndicadorDAO;
import com.bottrading.daos.VelaDAO;

public class ControladorIndicador {

    private final IndicadorDAO indicadorDAO;
    private final VelaDAO velaDAO;

    public ControladorIndicador() {
        this.indicadorDAO = IndicadorDAO.getInstance();
        this.velaDAO = VelaDAO.getInstance();
    }

    /* ===================== CÁLCULOS ===================== */

    public static void calcularIndicadoresBasicos(String symbol, String interval) {
        IndicatorsService indicatorsService = new IndicatorsService();
        // El DAO ya maneja el EntityManager internamente
        List<Vela> velas = VelaDAO.getInstance().findBySymbolAndInterval(symbol, interval);
        indicatorsService.calculateBasicIndicators(symbol, interval, velas);
    }

    /* ===================== PERSISTENCIA ===================== */

    public IndicadorTecnico guardarIndicador(IndicadorTecnicoDTO indicadorDTO) {
        // 1. Buscamos la vela usando el VelaDAO (sin pasar EntityManager)
        Vela vela = velaDAO.findById(indicadorDTO.getId());
        
        if (vela == null) {
            System.err.println("Error: No se encontró la vela con ID: " + indicadorDTO.getId());
            return null;
        }

        // 2. Mapeamos el DTO a la Entidad
        IndicadorTecnico indicador = new IndicadorTecnico();
        indicador.setVela(vela);
        indicador.setTipo(indicadorDTO.getTipo());
        indicador.setParametros(indicadorDTO.getParametros());
        indicador.setValor(indicadorDTO.getValor());

        System.out.println(String.format("Guardando indicador: %s [%s] para Vela ID: %d", 
                indicador.getTipo(), indicador.getParametros(), vela.getId()));

        // 3. Guardamos usando el IndicadorDAO
        return indicadorDAO.save(indicador);
    }

    /* ===================== CONSULTAS ===================== */

    public List<IndicadorTecnico> obtenerIndicadoresDeVela(Vela vela) {
        return indicadorDAO.findByVela(vela);
    }

    public boolean existeIndicador(Vela vela, String tipo, String parametros) {
        return indicadorDAO.exists(vela, tipo, parametros);
    }
}