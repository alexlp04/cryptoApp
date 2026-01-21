package com.bottrading.controllers;

import com.bottrading.Services.FetchService;
import com.bottrading.beans.Vela;
import com.bottrading.beans.VelaDTO;
import com.bottrading.daos.VelaDAO;

import java.util.List;

public class ControladorVela {

    private final VelaDAO velaDAO;

    public ControladorVela() {
        // Usamos el Singleton del DAO
        this.velaDAO = VelaDAO.getInstance();
    }

    /* ===================== CONSULTAS ===================== */

    public Vela obtenerVelaPorId(Long id) {
        // Usamos el método heredado de BaseEntityDAO
        return velaDAO.findById(id);
    }

    public Long getUltimoTimeStamp(String symbol, String interval) {
        // Usamos la implementación limpia que ya tienes en VelaDAO
        return velaDAO.getLastTimestamp(symbol, interval);
    }
    
    public List<Vela> obtenerVelas(String symbol, String interval) {
        return velaDAO.findBySymbolAndInterval(symbol, interval);
    }

    public Vela obtenerUltimaVela(String symbol, String interval) {
        return velaDAO.findLastVela(symbol, interval);
    }

    /* ===================== PERSISTENCIA ===================== */

    public void guardarVela(Vela vela) {
        velaDAO.save(vela);
    }

    public void guardarVela(VelaDTO velaDto) {
        // El VelaDAO ya tiene la lógica para convertir DTO a Entity
        velaDAO.save(velaDto);
    }

    /* ===================== SERVICIOS EXTERNOS ===================== */

    public static void actualizarDatos(String symbol, String interval) {
        FetchService fetchService = new FetchService();
        fetchService.fetch(symbol, interval);
    }
}