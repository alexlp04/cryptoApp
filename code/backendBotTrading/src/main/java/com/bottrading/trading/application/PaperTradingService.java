package com.bottrading.trading.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bottrading.trading.infrastructure.bridge.SignalDTO;
import com.bottrading.strategy.domain.EstadoEstrategia;
import com.bottrading.strategy.domain.InstanciaEstrategia;
import com.bottrading.strategy.domain.InstanciaEstrategiaRepository;
import com.bottrading.trading.domain.Posicion;
import com.bottrading.trading.domain.PosicionRepository;
import com.bottrading.trading.infrastructure.cache.StatsCache;
import com.bottrading.backtesting.infrastructure.FileService;
import com.bottrading.shared.utils.SafeParser;

import lombok.extern.slf4j.Slf4j;

/**
 * Servicio encargado de la ejecución simulada de órdenes (Paper Trading).
 * Recibe señales del motor de Python, gestiona el estado de las posiciones
 * y coordina los movimientos contables con {@link AccountingService}.
 */
@Slf4j
@Service
public class PaperTradingService {

    private final AccountingService accountingService;
    private final InstanciaEstrategiaRepository instanciaRepo;
    private final PosicionRepository posicionRepo;
    private final FileService fileService;
    private final StatsCache statsCache;

    @Autowired
    public PaperTradingService(AccountingService accountingService,
            InstanciaEstrategiaRepository instanciaRepo,
            PosicionRepository posicionRepo,
            FileService fileService,
            StatsCache statsCache) {
        this.accountingService = accountingService;
        this.instanciaRepo = instanciaRepo;
        this.posicionRepo = posicionRepo;
        this.fileService = fileService;
        this.statsCache = statsCache;
    }

    /**
     * Procesa una señal de trading (BUY/SELL) recibida desde el motor de
     * estrategia.
     * Valida el estado de la estrategia y delega la lógica específica de compra o
     * venta.
     *
     * @param instanciaId ID de la estrategia que generó la señal.
     * @param signal      DTO con los detalles de la señal (acción, precio, símbolo,
     *                    etc.).
     */
    @Transactional
    public void onSignal(Long instanciaId, SignalDTO signal) {
        InstanciaEstrategia instancia = instanciaRepo.findByIdWithLock(instanciaId)
                .orElseThrow(() -> new RuntimeException("Instancia no encontrada: " + instanciaId));

        if (EstadoEstrategia.ACTIVA != instancia.getEstado()) {
            return; // Ignorar señales de estrategias pausadas o detenidas
        }

        if ("BUY".equals(signal.getAction())) {
            handleBuy(instancia, signal);
        } else if ("SELL".equals(signal.getAction())) {
            handleSell(instancia, signal);
        }
    }

    /**
     * Ejecuta la lógica de apertura de una posición Larga (BUY).
     * Verifica fondos, registra la posición y actualiza la contabilidad.
     */
    private void handleBuy(InstanciaEstrategia e, SignalDTO signal) {
        // Evitar abrir múltiples posiciones sobre el mismo símbolo
        if (posicionRepo.existsByInstanciaAndSimboloAndAbiertaTrue(e, signal.getSymbol())) {
            return;
        }

        BigDecimal montoAInvertir = e.getCapitalReservado().multiply(e.getRiskPerTrade());

        // Validaciones básicas de gestión de riesgo
        if (montoAInvertir.compareTo(BigDecimal.ZERO) <= 0 ||
                montoAInvertir.compareTo(e.getCapitalReservado()) > 0) {
            log.error("Orden BUY rechazada: Capital insuficiente o riesgo inválido.");
            return;
        }

        // Bloquear capital en la cuenta
        accountingService.commitCapital(e.getId(), montoAInvertir, e.getRiskPerTrade());

        // Crear registro de posición
        Posicion pos = new Posicion();
        pos.setInstancia(e);
        pos.setSimbolo(signal.getSymbol());
        pos.setPrecioEntrada(signal.getPrice());
        pos.setMargenInvertido(montoAInvertir);
        pos.setAbierta(true);
        posicionRepo.save(pos);

        // Registro en archivo CSV
        fileService.guardarTrade(e.getNombreEstrategia(), signal.getTimeframe(), signal.getSymbol(), "BUY",
                signal.getPrice(), signal.getTimestamp(), null);
    }

    /**
     * Ejecuta la lógica de cierre de una posición (SELL).
     * Calcula PnL, actualiza balances y genera estadísticas.
     */
    private void handleSell(InstanciaEstrategia e, SignalDTO signal) {
        Posicion pos = posicionRepo.findByInstanciaAndSimboloAndAbiertaTrue(e, signal.getSymbol())
                .orElse(null);

        if (pos == null)
            return; // No hay nada que vender

        BigDecimal precioSalida = signal.getPrice();
        BigDecimal multiplicador = precioSalida.divide(pos.getPrecioEntrada(), 8, RoundingMode.HALF_UP);
        BigDecimal montoFinal = pos.getMargenInvertido().multiply(multiplicador);
        BigDecimal pnlNeto = montoFinal.subtract(pos.getMargenInvertido());

        // Liquidar contablemente: liberar el mismo riesgo que se comprometió al abrir
        accountingService.closeTrade(e.getWalletAsociada(), e.getId(), pos.getMargenInvertido(), pnlNeto,
                e.getRiskPerTrade());

        // Cerrar posición lógica
        pos.setAbierta(false);
        pos.setPrecioSalida(precioSalida);
        pos.setPnl(pnlNeto);
        pos.setFechaCierre(Instant.now());
        posicionRepo.save(pos);

        // Registro en archivo CSV
        fileService.guardarTrade(e.getNombreEstrategia(), signal.getTimeframe(), signal.getSymbol(), "SELL",
                signal.getPrice(), signal.getTimestamp(), pnlNeto);

        // Actualización de estadísticas agregadas
        Map<String, Object> statsActualizadas = calcularNuevasStats(e, signal.getSymbol(), pnlNeto);
        fileService.guardarStats(e.getNombreEstrategia(), signal.getTimeframe(), statsActualizadas, false);
    }

    /**
     * Recalcula las estadísticas acumuladas de la estrategia basándose en el caché
     * en memoria.
     * Ya NO lee del CSV cada vez (lo hace StatsCache automáticamente cada 30s).
     */
    private Map<String, Object> calcularNuevasStats(InstanciaEstrategia e, String symbol, BigDecimal pnlActual) {
        // Obtener stats actuales del caché (no del archivo)
        Map<String, Object> currentStats = statsCache.getStats(e.getNombreEstrategia(), e.getTimeframe(), symbol);

        // Parseo seguro usando SafeParser
        int ganadas = SafeParser.toInt(currentStats.get("op_ganadas"), 0);
        int perdidas = SafeParser.toInt(currentStats.get("op_perdidas"), 0);
        BigDecimal retornoTotal = SafeParser.toBigDecimal(currentStats.get("retorno_total"), BigDecimal.ZERO);

        // Actualizar contadores
        if (pnlActual.compareTo(BigDecimal.ZERO) > 0) {
            ganadas++;
        } else {
            perdidas++;
        }

        retornoTotal = retornoTotal.add(pnlActual);
        int totales = ganadas + perdidas;
        double winRate = (totales > 0) ? (double) ganadas / totales * 100 : 0;

        // Construir mapa actualizado
        Map<String, Object> newStats = new HashMap<>(currentStats);
        newStats.put("symbol", symbol);
        newStats.put("timeframe", e.getTimeframe());
        newStats.put("op_ganadas", ganadas);
        newStats.put("op_perdidas", perdidas);
        newStats.put("op_totales", totales);
        newStats.put("retorno_total", retornoTotal);
        newStats.put("win_rate", String.format("%.2f%%", winRate));
        newStats.put("resultado", retornoTotal.compareTo(BigDecimal.ZERO) >= 0 ? "PROFIT" : "LOSS");
        newStats.put("fecha_fin", new Date().toString());

        // Actualizar en caché (se guardará a disco automáticamente)
        statsCache.updateStats(e.getNombreEstrategia(), e.getTimeframe(), symbol, newStats);

        log.debug("Stats actualizadas en caché: {} ganadas, {} perdidas, win_rate: {}%",
                ganadas, perdidas, String.format("%.2f", winRate));
        return newStats;
    }
}