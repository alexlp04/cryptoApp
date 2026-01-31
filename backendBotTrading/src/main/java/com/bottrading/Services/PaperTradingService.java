package com.bottrading.services;

import com.bottrading.beans.*;
import com.bottrading.repositories.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

@Service
public class PaperTradingService {

    @Autowired
    private AccountingService accountingService;

    @Autowired
    private InstanciaEstrategiaRepository instanciaRepo;

    @Autowired
    private PosicionRepository posicionRepo;

    @Autowired
    private FileService fileService;

    @Transactional
    public void onSignal(Long instanciaId, SignalDTO signal) {
        InstanciaEstrategia instancia = instanciaRepo.findByIdWithLock(instanciaId)
                .orElseThrow(() -> new RuntimeException("Instancia no encontrada"));

        if (!"ACTIVA".equals(instancia.getEstado()))
            return;

        if ("BUY".equals(signal.getAction())) {
            handleBuy(instancia, signal);
        } else if ("SELL".equals(signal.getAction())) {
            handleSell(instancia, signal);
        }
    }

    private void handleBuy(InstanciaEstrategia e, SignalDTO signal) {
        if (posicionRepo.existsByInstanciaAndSimboloAndAbiertaTrue(e, signal.getSymbol()))
            return;

        // CORRECCIÓN: Multiplicación con BigDecimal
        BigDecimal montoAInvertir = e.getCapitalReservado().multiply(e.getRiskPerTrade());

        // CORRECCIÓN: Comparación con BigDecimal (monto > 0 && monto <= reservado)
        if (montoAInvertir.compareTo(BigDecimal.ZERO) <= 0 ||
                montoAInvertir.compareTo(e.getCapitalReservado()) > 0)
            return;
        // CORRECCIÓN: Asegúrate de que el Bean tenga el método o usa el atributo
        // adecuado
        // Si no tienes getWalletId(), usa la propiedad correcta (ej.
        // e.getWalletAsociada())
        // Aquí asumo que pasamos el ID o el objeto necesario
        accountingService.commitCapital(e.getId(), montoAInvertir, e.getRiskPerTrade());

        Posicion pos = new Posicion();
        pos.setInstancia(e);
        pos.setSimbolo(signal.getSymbol());
        pos.setPrecioEntrada(signal.getPrice());
        pos.setMargenInvertido(montoAInvertir);
        pos.setAbierta(true);
        posicionRepo.save(pos);

        fileService.guardarTrade(e.getNombreEstrategia(), signal.getTimeframe(), signal.getSymbol(), "BUY",
                signal.getPrice(), signal.getTimestamp(), null, e.getCapitalReservado(), false);
    }

    private void handleSell(InstanciaEstrategia e, SignalDTO signal) {
        Posicion pos = posicionRepo.findByInstanciaAndSimboloAndAbiertaTrue(e, signal.getSymbol())
                .orElse(null);
        if (pos == null)
            return;

        BigDecimal precioSalida = signal.getPrice();

        // CORRECCIÓN: División con BigDecimal (Salida / Entrada)
        BigDecimal multiplicador = (precioSalida).divide(pos.getPrecioEntrada(), 8, RoundingMode.HALF_UP);

        // CORRECCIÓN: MontoFinal = Margen * Multiplicador
        BigDecimal montoFinal = pos.getMargenInvertido().multiply(multiplicador);
        BigDecimal pnlNeto = montoFinal.subtract(pos.getMargenInvertido());

        // CORRECCIÓN: Uso de BigDecimal en la llamada al servicio contable
        // Ajusta los argumentos según la firma de tu AccountingService
        accountingService.closeTrade(e.getWalletAsociada(), e.getId(), pos.getMargenInvertido(), pnlNeto, BigDecimal.ZERO);

        pos.setAbierta(false);
        posicionRepo.save(pos);

        fileService.guardarTrade(e.getNombreEstrategia(), signal.getTimeframe(), signal.getSymbol(), "SELL",
                signal.getPrice(), signal.getTimestamp(), pnlNeto, e.getCapitalReservado(), false);

        fileService.guardarStats(e.getNombreEstrategia(), signal.getTimeframe(),
                Map.of("symbol", signal.getSymbol(), "pnl", pnlNeto), false);
    }

}