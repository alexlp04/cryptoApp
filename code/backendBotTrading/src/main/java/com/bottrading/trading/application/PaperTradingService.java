package com.bottrading.trading.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.bottrading.shared.utils.AppConstants;
import com.bottrading.shared.utils.SafeParser;
import com.bottrading.strategy.domain.EstadoEstrategia;
import com.bottrading.strategy.domain.InstanciaEstrategia;
import com.bottrading.strategy.domain.InstanciaEstrategiaRepository;
import com.bottrading.trading.application.port.in.AccountingUseCase;
import com.bottrading.trading.application.port.in.ProcessSignalUseCase;
import com.bottrading.trading.application.port.out.PosicionRepositoryPort;
import com.bottrading.trading.application.port.out.TradeResultsPort;
import com.bottrading.trading.domain.Posicion;
import com.bottrading.trading.infrastructure.bridge.ProcessedSignalRegistry;
import com.bottrading.trading.infrastructure.bridge.SignalDTO;
import com.bottrading.trading.infrastructure.cache.StatsCache;

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
    private final TradeResultsPort tradeResultsPort;
    private final StatsCache statsCache;
    private final ProcessedSignalRegistry signalRegistry;

    @Autowired
    public PaperTradingService(AccountingUseCase accountingService,
            InstanciaEstrategiaRepository instanciaRepo,
            PosicionRepositoryPort posicionRepo,
            TradeResultsPort tradeResultsPort,
            StatsCache statsCache,
            ProcessedSignalRegistry signalRegistry) {
        this.accountingService = accountingService;
        this.instanciaRepo = instanciaRepo;
        this.posicionRepo = posicionRepo;
        this.tradeResultsPort = tradeResultsPort;
        this.statsCache = statsCache;
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

        // Registro en archivo CSV (tras el commit: si la TX revierte y se reintenta no queda duplicado)
        despuesDelCommit(() -> tradeResultsPort.guardarTrade(e.getNombreEstrategia(), signal.getTimeframe(),
                signal.getSymbol(), "BUY", signal.getPrice(), signal.getTimestamp(), null));
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

        // CSV de trades y estadísticas agregadas: efectos fuera de la BBDD, solo si la TX se confirma.
        despuesDelCommit(() -> {
            tradeResultsPort.guardarTrade(e.getNombreEstrategia(), signal.getTimeframe(), signal.getSymbol(),
                    "SELL", signal.getPrice(), signal.getTimestamp(), pnlNeto);
            Map<String, Object> statsActualizadas = calcularNuevasStats(e, signal.getSymbol(), pnlNeto);
            tradeResultsPort.guardarStats(e.getNombreEstrategia(), signal.getTimeframe(), statsActualizadas, false);
        });
    }

    /**
     * Ejecuta el efecto tras el commit de la transacción en curso (o al momento si no hay ninguna).
     * El CSV y el caché no son transaccionales: dentro de la TX un rollback + reintento los
     * dejaba escritos dos veces.
     */
    private void despuesDelCommit(Runnable efecto) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            efecto.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    efecto.run();
                } catch (RuntimeException ex) {
                    // La operación ya está confirmada en BBDD (fuente de verdad); el CSV es derivado.
                    log.error("No se pudo escribir el CSV de la operación confirmada: {}", ex.getMessage(), ex);
                }
            }
        });
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

        // Actualizar contadores: un cierre con PnL 0 no es una pérdida (cuenta solo en el total,
        // igual que en backtest_engine.py).
        int signo = pnlActual.signum();
        if (signo > 0) {
            ganadas++;
        } else if (signo < 0) {
            perdidas++;
        }

        retornoTotal = retornoTotal.add(pnlActual);
        int totales = SafeParser.toInt(currentStats.get("op_totales"), ganadas + perdidas - (signo == 0 ? 0 : 1)) + 1;
        double winRate = (totales > 0) ? (double) ganadas / totales * 100 : 0;

        // Construir mapa actualizado
        Map<String, Object> newStats = new HashMap<>(currentStats);
        newStats.put("symbol", symbol);
        newStats.put("timeframe", e.getTimeframe());
        newStats.put("op_ganadas", ganadas);
        newStats.put("op_perdidas", perdidas);
        newStats.put("op_totales", totales);
        newStats.put("retorno_total", retornoTotal);
        newStats.put("win_rate", String.format(Locale.ROOT, "%.2f%%", winRate));
        newStats.put(AppConstants.KEY_RESULTADO, clasificarResultado(retornoTotal));
        newStats.put("fecha_fin", Instant.now().toString()); // ISO-8601, parseable

        // Actualizar en caché (se guardará a disco automáticamente)
        statsCache.updateStats(e.getNombreEstrategia(), e.getTimeframe(), symbol, newStats);

        log.debug("Stats actualizadas en caché: {} ganadas, {} perdidas, win_rate: {}%",
                ganadas, perdidas, String.format(Locale.ROOT, "%.2f", winRate));
        return newStats;
    }

    /**
     * Clasifica el retorno con el mismo vocabulario y los mismos cortes que
     * backtest_engine.py, que escribe en este mismo CSV.
     */
    private String clasificarResultado(BigDecimal retornoTotal) {
        int signo = retornoTotal.compareTo(BigDecimal.ZERO);
        if (signo > 0) {
            return AppConstants.RESULTADO_GANANCIA;
        }
        return signo < 0 ? AppConstants.RESULTADO_PERDIDA : AppConstants.RESULTADO_NEUTRO;
    }
}