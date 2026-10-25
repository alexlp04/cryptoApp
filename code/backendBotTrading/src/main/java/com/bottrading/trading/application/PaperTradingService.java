package com.bottrading.trading.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bottrading.strategy.domain.EstadoEstrategia;
import com.bottrading.strategy.domain.InstanciaEstrategia;
import com.bottrading.strategy.domain.InstanciaEstrategiaRepository;
import com.bottrading.trading.application.port.in.AccountingUseCase;
import com.bottrading.trading.application.port.in.ProcessSignalUseCase;
import com.bottrading.trading.application.port.out.PosicionRepositoryPort;
import com.bottrading.trading.domain.Posicion;
import com.bottrading.trading.infrastructure.bridge.ProcessedSignalRegistry;
import com.bottrading.trading.infrastructure.bridge.SignalDTO;

import lombok.extern.slf4j.Slf4j;

/**
 * Servicio encargado de la ejecución simulada de órdenes (Paper Trading).
 * Recibe señales del motor de Python, gestiona el estado de las posiciones
 * y coordina los movimientos contables con {@link AccountingService}.
 */
@Slf4j
@Service
public class PaperTradingService implements ProcessSignalUseCase {

    private final AccountingUseCase accountingService;
    private final InstanciaEstrategiaRepository instanciaRepo;
    private final PosicionRepositoryPort posicionRepo;
    private final ProcessedSignalRegistry signalRegistry;

    @Autowired
    public PaperTradingService(AccountingUseCase accountingService,
            InstanciaEstrategiaRepository instanciaRepo,
            PosicionRepositoryPort posicionRepo,
            ProcessedSignalRegistry signalRegistry) {
        this.accountingService = accountingService;
        this.instanciaRepo = instanciaRepo;
        this.posicionRepo = posicionRepo;
        this.signalRegistry = signalRegistry;
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
    @Override
    public void onSignal(Long instanciaId, SignalDTO signal) {
        // Idempotencia: una señal ya aplicada con éxito (reentregada o reintentada
        // tras un fallo posterior) no debe volver a ejecutarse.
        String idempotencyKey = signal.idempotencyKey();
        if (signalRegistry.seen(instanciaId, idempotencyKey)) {
            log.debug("Señal duplicada ignorada por idempotencia: [{}] {} {} @ {}",
                    instanciaId, signal.getAction(), signal.getSymbol(), signal.getTimestamp());
            return;
        }

        InstanciaEstrategia instancia = instanciaRepo.findByIdWithLock(instanciaId)
                .orElseThrow(() -> new RuntimeException("Instancia no encontrada: " + instanciaId));

        if (EstadoEstrategia.ACTIVA != instancia.getEstado()) {
            return; // Ignorar señales de estrategias pausadas o detenidas
        }

        if (!senalCorrespondeALaInstancia(instancia, signal)) {
            return;
        }

        if ("BUY".equals(signal.getAction())) {
            handleBuy(instancia, signal);
        } else if ("SELL".equals(signal.getAction())) {
            handleSell(instancia, signal);
        }

        // Marcar solo tras aplicar con éxito: si algún handle lanzó, no se marca y
        // el reintento podrá volver a intentarlo (la @Transactional revierte el efecto parcial).
        signalRegistry.mark(instanciaId, idempotencyKey);
    }

    /**
     * Descarta con aviso (en vez de en silencio) las señales que no son operables: acción
     * desconocida, precio no positivo, o símbolo/timeframe que la instancia no tiene configurados.
     */
    private boolean senalCorrespondeALaInstancia(InstanciaEstrategia instancia, SignalDTO signal) {
        String action = signal.getAction();
        if (!"BUY".equals(action) && !"SELL".equals(action)) {
            log.warn("[{}] Señal descartada: acción desconocida '{}'", instancia.getId(), action);
            return false;
        }
        if (signal.getPrice() == null || signal.getPrice().signum() <= 0) {
            log.warn("[{}] Señal {} {} descartada: precio inválido {}",
                    instancia.getId(), action, signal.getSymbol(), signal.getPrice());
            return false;
        }
        boolean simboloConocido = instancia.getSimbolos() != null && instancia.getSimbolos().stream()
                .anyMatch(s -> s.equalsIgnoreCase(signal.getSymbol()));
        if (!simboloConocido) {
            log.warn("[{}] Señal descartada: símbolo {} no pertenece a la estrategia {}",
                    instancia.getId(), signal.getSymbol(), instancia.getSimbolos());
            return false;
        }
        if (signal.getTimeframe() != null && instancia.getTimeframe() != null
                && !signal.getTimeframe().equalsIgnoreCase(instancia.getTimeframe())) {
            log.warn("[{}] Señal descartada: timeframe {} distinto del configurado {}",
                    instancia.getId(), signal.getTimeframe(), instancia.getTimeframe());
            return false;
        }
        return true;
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

        BigDecimal montoAInvertir = e.getCapitalReservado().multiply(e.getRiskPerTrade())
                .setScale(8, RoundingMode.HALF_UP); // la escala de la columna: lo sumado en memoria es lo guardado

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
        log.info("[{}] BUY {} @ {} (margen {})", e.getId(), signal.getSymbol(), signal.getPrice(), montoAInvertir);
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
        BigDecimal pnlNeto = calcularPnl(pos, precioSalida);

        // Liquidar contablemente: liberar el mismo riesgo que se comprometió al abrir
        accountingService.closeTrade(e.getWalletAsociada(), e.getId(), pos.getMargenInvertido(), pnlNeto,
                e.getRiskPerTrade());

        // Cerrar posición lógica
        pos.setAbierta(false);
        pos.setPrecioSalida(precioSalida);
        pos.setPnl(pnlNeto);
        pos.setFechaCierre(Instant.now());
        posicionRepo.save(pos);
        log.info("[{}] SELL {} @ {} (PnL {})", e.getId(), signal.getSymbol(), precioSalida, pnlNeto);
    }

    /**
     * PnL = margen * (salida - entrada) / entrada con una sola división al final (una división
     * intermedia a 8 decimales arrastraba el error al multiplicar por el margen).
     */
    private BigDecimal calcularPnl(Posicion pos, BigDecimal precioSalida) {
        BigDecimal entrada = pos.getPrecioEntrada();
        if (entrada == null || entrada.signum() <= 0) {
            // Posición corrupta heredada: se cierra sin PnL en vez de quedar imposible de cerrar.
            log.error("Posición {} con precio de entrada inválido ({}): se cierra con PnL 0", pos.getId(), entrada);
            return BigDecimal.ZERO;
        }
        return pos.getMargenInvertido().multiply(precioSalida.subtract(entrada))
                .divide(entrada, 8, RoundingMode.HALF_UP);
    }

}