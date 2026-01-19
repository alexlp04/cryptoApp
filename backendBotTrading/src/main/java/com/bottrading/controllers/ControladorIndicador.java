package com.bottrading.controllers;

import java.util.List;

import com.bottrading.Services.IndicatorsService;
import com.bottrading.Utils.WebSession;
import com.bottrading.beans.IndicadorTecnico;
import com.bottrading.beans.IndicadorTecnicoDTO;
import com.bottrading.beans.Vela;
import com.bottrading.daos.IndicadorDAO;
import com.bottrading.daos.VelaDAO;

import jakarta.persistence.EntityManager;

public class ControladorIndicador {

    private IndicadorDAO indicadorDAO = IndicadorDAO.getInstance();

    public ControladorIndicador() {
    }

    public static void calcularIndicadoresBasicos(String symbol, String interval) {
        IndicatorsService indicatorsService = new IndicatorsService();
        List<Vela> velas = VelaDAO.getInstance().findBySymbolAndInterval(symbol, interval);
        indicatorsService.calculateBasicIndicators(symbol, interval, velas);
    }

    public IndicadorTecnico guardarIndicador(IndicadorTecnicoDTO indicadorDTO) {
        IndicadorTecnico indicador = new IndicadorTecnico();
        EntityManager em = WebSession.getInstance().getEntityManager();
        System.out.println("Guardando indicador para la vela ID: " + indicadorDTO.getId() + ", tipo: " + indicadorDTO.getTipo() + ", parametros: " + indicadorDTO.getParametros() + ", valor: " + indicadorDTO.getValor());
        indicador.setVela(VelaDAO.getInstance().findById(em,indicadorDTO.getId()));
        indicador.setTipo(indicadorDTO.getTipo());
        indicador.setParametros(indicadorDTO.getParametros());
        indicador.setValor(indicadorDTO.getValor());
        return indicadorDAO.save(indicador);
    }

}
