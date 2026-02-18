package com.bottrading.services;

import com.bottrading.beans.InstanciaEstrategia;
import com.bottrading.beans.Vela;
import com.bottrading.repositories.InstanciaEstrategiaRepository;
import com.bottrading.repositories.VelaRepository;
import com.bottrading.utils.AppConstants;
import com.bottrading.utils.ConsoleLoader;
import com.bottrading.utils.PathConfig;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.math.BigDecimal;
import java.util.*;

/**
 * Servicio encargado de la orquestación del ciclo de vida de las estrategias.
 * Gestiona el inicio, parada (pausa), terminación (liquidación) y consultas de
 * estado.
 */
@Slf4j
@Service
public class EstrategiaService {

    private final InstanciaEstrategiaRepository instanciaRepo;
    private final VelaRepository velaRepo;
    private final AccountingService accountingService;
    private final TradingService tradingService;
    private final BacktestingService backtestingService;
    private final FileService fileService;

    @Autowired
    public EstrategiaService(InstanciaEstrategiaRepository instanciaRepo, VelaRepository velaRepo,
            AccountingService accountingService, TradingService tradingService,
            BacktestingService backtestingService, FileService fileService) {
        this.instanciaRepo = instanciaRepo;
        this.velaRepo = velaRepo;
        this.accountingService = accountingService;
        this.tradingService = tradingService;
        this.backtestingService = backtestingService;
        this.fileService = fileService;
    }

    // ========================================================================
    // SECCIÓN 1: GESTIÓN DE CICLO DE VIDA (START / STOP / TERMINATE)
    // ========================================================================

    /**
     * Inicia una nueva instancia de estrategia en Tiempo Real.
     * 1. Crea el registro en BBDD.
     * 2. Reserva el capital (AccountingService).
     * 3. Lanza el proceso de Python (TradingService).
     *
     * @param nombreEstra Nombre del archivo .py
     * @param tf          Timeframe (ej: 1m, 1h)
     * @param coins       Lista de símbolos
     * @param isReal      True para usar dinero real (mock), False para paper
     * @param walletId    ID de la billetera origen
     * @param risk        Riesgo por operación (0.01 = 1%)
     * @param capital     Capital inicial a reservar
     */
    @Transactional
    public void iniciarTradeRT(String nombreEstra, String tf, List<String> coins,
            boolean isReal, Long walletId, BigDecimal risk, BigDecimal capital) {

        InstanciaEstrategia instancia = new InstanciaEstrategia();
        instancia.setNombreEstrategia(nombreEstra);
        instancia.setTimeframe(tf);
        instancia.setCapitalAsignado(capital);
        instancia.setRiskPerTrade(risk);
        instancia.setSimbolos(new ArrayList<>(coins));
        instancia.setEsReal(isReal);
        instancia.setWalletAsociada(walletId);
        instancia.setCapitalReservado(capital);
        instancia.setCapitalComprometido(BigDecimal.ZERO);
        instancia.setRiesgoAbierto(BigDecimal.ZERO);
        instancia.setEliminado(false);
        instancia.setEstado("CREADA");

        // Guardar antes de activar para tener ID
        instancia = instanciaRepo.save(instancia);

        // Activar contablemente (Mueve saldo de Disponible -> Reservado)
        accountingService.activateStrategy(walletId, instancia.getId(), capital);

        // Lanzar motor técnico
        tradingService.ejecutarTradeEnTiempoReal(instancia, coins);
    }

    /**
     * COMANDO START: Reanuda una estrategia detenida.
     * Solo funciona si el estado es DETENIDA.
     */
    public void iniciarEstrategiaDetenida(Long instanciaId) {
        Optional<InstanciaEstrategia> instancia = instanciaRepo.findById(instanciaId);
        if (!instancia.isPresent()) {
            log.error("No se encontró la estrategia con ID: " + instanciaId);
            return;
        }
        InstanciaEstrategia instanciaObj = instancia.get();
        if (!AppConstants.KEY_DETENIDA.equals(instanciaObj.getEstado())) {
            log.error("Solo se pueden iniciar estrategias en estado DETENIDA. Estado actual: "
                    + instanciaObj.getEstado());
            return;
        }

        // Reactivamos
        instanciaObj.setEstado(AppConstants.KEY_ACTIVA);
        instanciaRepo.save(instanciaObj);

        // Volvemos a lanzar el hilo de Python
        tradingService.ejecutarTradeEnTiempoReal(instanciaObj, instanciaObj.getSimbolos());
        log.info("Estrategia {} reanudada correctamente.", instanciaId);
    }

    public void iniciarTodasDetenidas() {
        List<InstanciaEstrategia> detenidas = instanciaRepo.findByEstado(AppConstants.KEY_DETENIDA);
        if (detenidas.isEmpty()) {
            log.info("No hay estrategias detenidas para iniciar.");
            return;
        }
        log.info("Iniciando {} estrategias detenidas...", detenidas.size());
        detenidas.forEach(inst -> iniciarEstrategiaDetenida(inst.getId()));
    }

    /**
     * COMANDO TERM: Finaliza y liquida la estrategia.
     * - Detiene el hilo de Python.
     * - Devuelve el capital remanente a la Wallet.
     * - Cambia estado a FINALIZADA.
     *
     * @param instanciaId ID de la estrategia
     */
    public void terminarEstrategia(long instanciaId) {
        // 1. Detener proceso físico
        tradingService.detenerEstrategia(instanciaId);

        // 2. Liquidación financiera
        InstanciaEstrategia instancia = instanciaRepo.findById(instanciaId).orElse(null);
        if (instancia != null && !AppConstants.KEY_TERMINADA.equals(instancia.getEstado())) {
            if (instancia.getWalletAsociada() != null) {
                // AccountingService mueve el dinero y cierra el registro
                accountingService.closeStrategy(instancia.getWalletAsociada(), instancia.getId());
                log.info("Estrategia {} -> TERMINADA (Fondos retornados a Wallet).", instanciaId);
            }
        } else {
            log.info("La estrategia no existe o ya estaba finalizada.");
            return;
        }
    }

    public void terminarTodas() {
        Set<Long> ids = tradingService.getIdsEstrategiasActivas();
        if (ids.isEmpty()) {
            log.info("No hay estrategias activas para terminar.");
            return;
        }
        log.info("Terminando {} estrategias...", ids.size());
        ids.stream().forEach(this::terminarEstrategia);
        log.info("Todas las estrategias han sido finalizadas.");
    }

    /**
     * COMANDO STOP: Pausa la ejecución de la estrategia.
     * - Detiene el hilo de Python.
     * - Cambia estado a DETENIDA.
     * - MANTIENE el capital reservado (no devuelve fondos).
     *
     * @param instanciaId ID de la estrategia
     */
    public void detenerEstrategia(long instanciaId) {
        // 1. Detener proceso físico inmediatamente
        tradingService.detenerEstrategia(instanciaId);

        // 2. Actualizar estado administrativo
        InstanciaEstrategia instanciaObj = instanciaRepo.findById(instanciaId).orElse(null);
        if (instanciaObj != null && (AppConstants.KEY_ACTIVA.equals(instanciaObj.getEstado()))) {

            instanciaObj.setEstado(AppConstants.KEY_DETENIDA);
            instanciaRepo.save(instanciaObj);
            log.info("Estrategia {} -> DETENIDA (Fondos mantenidos en reserva).", instanciaId);

        }
    }

    /**
     * Detiene (Pausa) todas las estrategias activas en memoria.
     * Se usa para paradas de emergencia o cierre del sistema.
     */
    public void detenerTodas() {
        Set<Long> ids = tradingService.getIdsEstrategiasActivas();
        if (ids.isEmpty()) {
            log.info("No hay estrategias activas para detener.");
            return;
        }
        log.info("Pausando {} estrategias...", ids.size());
        ids.stream().forEach(this::detenerEstrategia);
        log.info("Todas las estrategias han sido pausadas.");
    }

    // ========================================================================
    // SECCIÓN 2: CONSULTAS E INFORMACIÓN (LISTADOS)
    // ========================================================================

    public List<String> listarEstrategias() {
        List<InstanciaEstrategia> lista = instanciaRepo.findAll();
        return lista.stream()
                .map(InstanciaEstrategia::toString)
                .toList();
    }

    public List<String> listarEstrategiasActivas() {
        return listarPorEstado("ACTIVA", "No hay estrategias activas.");
    }

    public List<String> listarEstrategiasTerminadas() {
        return listarPorEstado("TERMINADA", "No hay estrategias finalizadas.");
    }

    public List<String> listarEstrategiasDetenidas() {
        return listarPorEstado("DETENIDA", "No hay estrategias detenidas.");
    }

    private List<String> listarPorEstado(String estado, String mensajeVacio) {
        List<InstanciaEstrategia> lista = instanciaRepo.findByEstado(estado);
        if (lista.isEmpty()) {
            return List.of(mensajeVacio);
        }
        return lista.stream()
                .map(InstanciaEstrategia::toString)
                .toList();
    }

    /**
     * Lista los archivos .py físicos disponibles en la carpeta de estrategias.
     */
    public List<String> listarFicherosDeEstrategias() {
        File folder = new File(PathConfig.STRATEGIES_DIR);
        File[] files = folder.listFiles((dir, name) -> name.endsWith(".py"));

        if (files == null || files.length == 0) {
            log.info("No se encontraron archivos en: {}", PathConfig.STRATEGIES_DIR);
            return List.of("No se encontraron archivos en: " + PathConfig.STRATEGIES_DIR);
        }

        return Arrays.stream(files)
                .map(f -> "- " + f.getName().replace(".py", ""))
                .toList();
    }

    public BigDecimal getCapitalComprometido(Long walletAsociada) {
        Double sum = instanciaRepo.sumCapitalActivoByWallet(walletAsociada);
        return sum != null ? BigDecimal.valueOf(sum) : BigDecimal.ZERO;
    }

    // ========================================================================
    // SECCIÓN 3: BACKTESTING
    // ========================================================================

    public void ejecutarBacktest(String nombreEstra, String tf, List<String> coins) throws Exception {
        fileService.verificarYLimpiarCarpetaEstrategia(nombreEstra);
        ConsoleLoader.getInstance().startDots();

        // 1. Obtener datos históricos
        Map<String, List<Vela>> velasPorSimbolo = new HashMap<>();
        for (String symbol : coins) {
            List<Vela> velas = velaRepo.findBySymbolAndIntervalOrderByOpenTimeAsc(symbol, tf);
            velasPorSimbolo.put(symbol, velas);
            if (velas.isEmpty()) {
                ConsoleLoader.getInstance().stop();
                log.warn("Advertencia: No hay velas para {} en {}", symbol, tf);
                ConsoleLoader.getInstance().startDots();
            }
        }

        if (velasPorSimbolo.isEmpty()) {
            ConsoleLoader.getInstance().stop();
            log.error("Abortando: Sin datos para procesar.");
            return;
        }

        // 2. Ejecutar motor
        String strategyPath = PathConfig.getValidStrategyPath(nombreEstra);
        String jsonResultado = backtestingService.ejecutarBacktest(strategyPath, tf, velasPorSimbolo);
        ConsoleLoader.getInstance().stop();

        // 3. Guardar resultados
        if (jsonResultado != null && !jsonResultado.isEmpty()) {
            fileService.guardarResultadosCompletos(nombreEstra, tf, jsonResultado);
            log.info("Resultados guardados en: {}{}{}", PathConfig.RESULTS_DIR, File.separator, nombreEstra);
        } else {
            log.error("El motor de backtest no devolvió resultados.");
        }
    }
}
