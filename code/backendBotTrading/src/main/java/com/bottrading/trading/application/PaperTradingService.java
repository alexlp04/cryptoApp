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
import com.bottrading.trading.domain.ExecutionCosts;
import com.bottrading.trading.domain.Posicion;
import com.bottrading.trading.domain.RiskRules;
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

        if (instancia.isEsReal()) {
            // Sin adaptador de exchange una orden "real" solo sería una simulación con otro nombre.
            log.error("[{}] Señal ignorada: la estrategia es real y este servicio solo simula (paper trading)",
                    instancia.getId());
            return;
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
     * Abre una posición larga como lo haría una plataforma de trading: el tamaño sale del riesgo y del stop
     * de la señal ({@link RiskRules}), la orden se llena con slippage ({@link ExecutionCosts}) y se rechaza
     * si no hay stop válido, si no cabe en el capital o si superaría el riesgo abierto permitido.
     */
    private void handleBuy(InstanciaEstrategia e, SignalDTO signal) {
        // Evitar abrir múltiples posiciones sobre el mismo símbolo
        if (posicionRepo.existsByInstanciaAndSimboloAndAbiertaTrue(e, signal.getSymbol())) {
            return;
        }

        BigDecimal entrada = ExecutionCosts.precioCompra(signal.getPrice());
        if (!RiskRules.stopValido(entrada, signal.getStopLoss())) {
            log.warn("[{}] BUY {} rechazado: la señal no trae un stop válido por debajo de la entrada ({} / {})",
                    e.getId(), signal.getSymbol(), entrada, signal.getStopLoss());
            return;
        }

        BigDecimal equity = e.getCapitalReservado().add(e.getCapitalComprometido());
        RiskRules.Sizing tamano = RiskRules.dimensionar(
                equity, e.getRiskPerTrade(), entrada, signal.getStopLoss(), e.getCapitalReservado());
        if (tamano == null) {
            log.warn("[{}] BUY {} rechazado: sin capital para una posición con ese stop (equity {}, reservado {})",
                    e.getId(), signal.getSymbol(), equity, e.getCapitalReservado());
            return;
        }
        if (RiskRules.superaRiesgoAbierto(equity, e.getRiesgoAbierto(), tamano.riesgo())) {
            log.warn("[{}] BUY {} rechazado: el riesgo abierto ({} + {}) superaría el {} % del equity",
                    e.getId(), signal.getSymbol(), e.getRiesgoAbierto(), tamano.riesgo(),
                    RiskRules.MAX_OPEN_RISK_FRACTION.movePointRight(2).stripTrailingZeros().toPlainString());
            return;
        }

        // Primero la posición (para tener su id) y luego el capital: la referencia del ledger la cita.
        Posicion pos = new Posicion();
        pos.setInstancia(e);
        pos.setSimbolo(signal.getSymbol());
        pos.setPrecioEntrada(entrada);
        pos.setUnidades(tamano.unidades());
        pos.setMargenInvertido(tamano.nocional());
        pos.setStopLoss(signal.getStopLoss());
        pos.setTakeProfit(signal.getTakeProfit());
        pos.setRiesgo(tamano.riesgo());
        pos.setAbierta(true);
        posicionRepo.save(pos);

        accountingService.commitCapital(e.getId(), tamano.nocional(), tamano.riesgo(),
                "posicion:" + pos.getId() + ":open");
        log.info("[{}] BUY {} {} u. @ {} (margen {}, riesgo {}, stop {})", e.getId(), signal.getSymbol(),
                tamano.unidades(), entrada, tamano.nocional(), tamano.riesgo(), signal.getStopLoss());
    }

    /**
     * Cierra una posición (SELL, STOP u objetivo). El PnL es neto de slippage y comisiones, como en el
     * backtest, y libera el riesgo que se comprometió al abrir.
     */
    private void handleSell(InstanciaEstrategia e, SignalDTO signal) {
        Posicion pos = posicionRepo.findByInstanciaAndSimboloAndAbiertaTrue(e, signal.getSymbol())
                .orElse(null);

        if (pos == null) {
            log.debug("[{}] SELL {} ignorado: no hay posición abierta", e.getId(), signal.getSymbol());
            return;
        }

        boolean ordenLimite = "TARGET".equals(signal.getReason());
        BigDecimal precioSalida = ExecutionCosts.precioVenta(signal.getPrice(), ordenLimite);
        BigDecimal comisiones = calcularComisiones(pos, precioSalida);
        BigDecimal pnlNeto = calcularPnl(pos, precioSalida).subtract(comisiones);

        accountingService.closeTrade(e.getWalletAsociada(), e.getId(), pos.getMargenInvertido(), pnlNeto,
                pos.getRiesgo() != null ? pos.getRiesgo() : BigDecimal.ZERO,
                "posicion:" + pos.getId() + ":close");

        pos.setAbierta(false);
        pos.setPrecioSalida(precioSalida);
        pos.setComisiones(comisiones);
        pos.setPnl(pnlNeto);
        pos.setFechaCierre(Instant.now());
        posicionRepo.save(pos);
        log.info("[{}] SELL {} @ {} ({}; PnL neto {}, comisiones {})", e.getId(), signal.getSymbol(),
                precioSalida, signal.getReason() != null ? signal.getReason() : "SIGNAL", pnlNeto, comisiones);
    }

    private BigDecimal calcularComisiones(Posicion pos, BigDecimal precioSalida) {
        if (pos.getUnidades() == null || pos.getPrecioEntrada() == null) {
            return BigDecimal.ZERO; // posición anterior a la gestión de riesgo: sin datos para calcularlas
        }
        return ExecutionCosts.comision(pos.getUnidades(), pos.getPrecioEntrada(), precioSalida);
    }

    /**
     * PnL bruto = unidades * (salida - entrada). Las posiciones anteriores a la gestión de riesgo no
     * guardan unidades: se usa margen * (salida - entrada) / entrada, con una sola división al final.
     */
    private BigDecimal calcularPnl(Posicion pos, BigDecimal precioSalida) {
        BigDecimal entrada = pos.getPrecioEntrada();
        if (entrada == null || entrada.signum() <= 0) {
            // Posición corrupta heredada: se cierra sin PnL en vez de quedar imposible de cerrar.
            log.error("Posición {} con precio de entrada inválido ({}): se cierra con PnL 0", pos.getId(), entrada);
            return BigDecimal.ZERO;
        }
        if (pos.getUnidades() != null) {
            return pos.getUnidades().multiply(precioSalida.subtract(entrada)).setScale(8, RoundingMode.HALF_UP);
        }
        return pos.getMargenInvertido().multiply(precioSalida.subtract(entrada))
                .divide(entrada, 8, RoundingMode.HALF_UP);
    }

}