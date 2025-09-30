package com.bottrading.controllers;

import com.bottrading.beans.Vela;
import com.bottrading.beans.VelaDTO;
import com.bottrading.daos.VelaDAO;

public class ControladorVela  {

    private VelaDAO velaDAO;

    public ControladorVela() {
        this.velaDAO = VelaDAO.getInstance();
    }

    public void guardarVela(Vela vela) {
        velaDAO.save(vela);
    }

    public void guardarVela(VelaDTO vela) {
        velaDAO.save(vela);
    }

}
