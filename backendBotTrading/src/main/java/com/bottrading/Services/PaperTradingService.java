package com.bottrading.Services;

import com.bottrading.beans.SignalDTO;
import com.bottrading.beans.InstanciaEstrategia;
import com.bottrading.controllers.ControladorWallet;
import com.bottrading.daos.InstanciaEstrategiaDAO;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PaperTradingService {

    private final String nombreWallet;
    private final InstanciaEstrategia instancia;
    private final ControladorWallet controladorWallet;
    private final SaveFileService saveFileService;

    // Rastrear precio de entrada
    private static final ConcurrentHashMap<String, Double> openPositions = new ConcurrentHashMap<>();
    // Rastrear monto exacto invertido (en USD) para cerrar el silo con precisión
    private static final ConcurrentHashMap<String, Double> montoInvertidoExacto = new ConcurrentHashMap<>();

    public PaperTradingService(InstanciaEstrategia instancia, String nombreWallet) {
        this.instancia = instancia;
        this.nombreWallet = nombreWallet;
        this.controladorWallet = new ControladorWallet();
        this.saveFileService = new SaveFileService(instancia.getNombreEstrategia());
    }

    public synchronized void onSignal(SignalDTO signal) {
        // Verificación de seguridad: si la instancia se quedó sin fondos, no opera
        if (!instancia.estaActiva()) {
            System.out.println("Instancia [" + instancia.getId() + "] sin fondos suficientes para operar.");
            return;
        }

        String key = signal.getSymbol() + "_" + signal.getTimeframe();
        
        if ("BUY".equals(signal.getAction())) {
            handleBuy(key, signal);
        } else if ("SELL".equals(signal.getAction())) {
            handleSell(key, signal);
        }
    }

    private void handleBuy(String key, SignalDTO signal) {
        if (openPositions.containsKey(key)) return;

        // 1. El capital se basa en el SILO de la instancia, no en la wallet total
        double balanceSilo = instancia.getCapitalAsignadoActual();
        double montoAInvertir = balanceSilo * instancia.getRiskPerTrade();

        if (montoAInvertir <= 0) return;

        // 2. Ejecutar transacción real en la base de datos (Bloqueo Pesimista)
        if (controladorWallet.intentarCompra(nombreWallet, montoAInvertir)) {
            openPositions.put(key, signal.getPrice());
            montoInvertidoExacto.put(key, montoAInvertir);

            // 3. Actualizar balance local de la instancia y persistir en DB
            double nuevoBalanceSilo = balanceSilo - montoAInvertir;
            actualizarSilo(nuevoBalanceSilo);

            // 4. Registrar logs
            registrarTrade(signal, "BUY", 0, nuevoBalanceSilo);

        }
    }

    private void handleSell(String key, SignalDTO signal) {
        if (!openPositions.containsKey(key)) return;

        double precioEntrada = openPositions.remove(key);
        double inversionOriginal = montoInvertidoExacto.remove(key);
        double precioSalida = signal.getPrice();
        
        // 1. Calcular PnL real basado en el multiplicador de precio
        double multiplicador = precioSalida / precioEntrada;
        double montoADevolver = inversionOriginal * multiplicador;
        double pnlNeto = montoADevolver - inversionOriginal;

        // 2. Aumentar balance en Wallet física (BD)
        controladorWallet.registrarVenta(nombreWallet, montoADevolver);

        // 3. Actualizar Silo de la instancia (Interés compuesto local)
        double nuevoBalanceSilo = instancia.getCapitalAsignadoActual() + montoADevolver;
        actualizarSilo(nuevoBalanceSilo);

        // 4. Registrar logs y estadísticas
        registrarTrade(signal, "SELL", pnlNeto, nuevoBalanceSilo);
        actualizarEstadisticas(signal, pnlNeto, multiplicador);

    }

    private void actualizarSilo(double nuevoMonto) {
        this.instancia.setCapitalAsignadoActual(nuevoMonto);
        // Persistencia en la tabla instancias_estrategia
        InstanciaEstrategiaDAO.getInstance().updateCapital(instancia.getId(), nuevoMonto);
    }

    private void registrarTrade(SignalDTO signal, String side, double pnl, double capitalSilo) {
        Map<String, Object> tradeLog = new HashMap<>();
        tradeLog.put("symbol", signal.getSymbol());
        tradeLog.put("timeframe", signal.getTimeframe());
        tradeLog.put("side", side);
        tradeLog.put("price", signal.getPrice());
        tradeLog.put("timestamp", System.currentTimeMillis() / 1000);
        tradeLog.put("pnl", pnl != 0 ? pnl : "");
        tradeLog.put("capital", capitalSilo);

        saveFileService.guardarTrade(signal.getTimeframe(), tradeLog);
    }

    private void actualizarEstadisticas(SignalDTO signal, double pnlNeto, double multiplicador) {
        Map<String, Object> stats = new HashMap<>();
        stats.put("symbol", signal.getSymbol());
        stats.put("timeframe", signal.getTimeframe());
        stats.put("op_ganadas", pnlNeto > 0 ? 1 : 0);
        stats.put("op_perdidas", pnlNeto <= 0 ? 1 : 0);
        stats.put("op_totales", 1);
        stats.put("retorno_acumulado", pnlNeto);
        stats.put("retorno_total", (multiplicador - 1));
        stats.put("win_rate", pnlNeto > 0 ? 1 : 0);
        stats.put("resultado", pnlNeto > 0 ? "GANANCIA" : "PERDIDA");
        stats.put("fecha_fin", System.currentTimeMillis() / 1000);

        saveFileService.guardarResultados(signal.getTimeframe(), stats);
    }
}