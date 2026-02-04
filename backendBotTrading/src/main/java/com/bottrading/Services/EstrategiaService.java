package com.bottrading.services;

import com.bottrading.beans.InstanciaEstrategia;
import com.bottrading.beans.Vela;
import com.bottrading.repositories.InstanciaEstrategiaRepository;
import com.bottrading.repositories.VelaRepository;
import com.bottrading.utils.ConsoleLoader;
import com.bottrading.utils.PathConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Servicio encargado de la orquestación del ciclo de vida de las estrategias.
 * Gestiona el inicio, parada (pausa), terminación (liquidación) y consultas de estado.
 */
@Service
public class EstrategiaService {

    @Autowired
    private InstanciaEstrategiaRepository instanciaRepo;
    @Autowired
    private VelaRepository velaRepo;
    @Autowired
    private AccountingService accountingService;
    @Autowired
    private TradingService tradingService;
    @Autowired
    private BacktestingService backtestingService;
    @Autowired
    private FileService fileService;

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
     * @param tf Timeframe (ej: 1m, 1h)
     * @param coins Lista de símbolos
     * @param isReal True para usar dinero real (mock), False para paper
     * @param walletId ID de la billetera origen
     * @param risk Riesgo por operación (0.01 = 1%)
     * @param capital Capital inicial a reservar
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
        InstanciaEstrategia instancia = instanciaRepo.findById(instanciaId).orElse(null);
        if (instancia != null) {
            // Solo actualizamos si estaba activa para mantener consistencia
            if ("ACTIVA".equals(instancia.getEstado()) || "CREADA".equals(instancia.getEstado())) {
                instancia.setEstado("DETENIDA");
                instanciaRepo.save(instancia);
                System.out.println("Estrategia " + instanciaId + " -> DETENIDA (Fondos mantenidos en reserva).");
            }
        }
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
        if (instancia != null && !"FINALIZADA".equals(instancia.getEstado())) {
            if (instancia.getWalletAsociada() != null) {
                // AccountingService mueve el dinero y cierra el registro
                accountingService.closeStrategy(instancia.getWalletAsociada(), instancia.getId());
                System.out.println("Estrategia " + instanciaId + " -> FINALIZADA (Fondos retornados a Wallet).");
            }
        } else {
            System.out.println("La estrategia no existe o ya estaba finalizada.");
        }
    }

    /**
     * Detiene (Pausa) todas las estrategias activas en memoria.
     * Se usa para paradas de emergencia o cierre del sistema.
     */
    public void detenerTodas() {
        Set<Long> ids = tradingService.getIdsEstrategiasActivas();
        if (ids.isEmpty()) {
            System.out.println("No hay estrategias activas para detener.");
            return;
        }

        System.out.println("Pausando " + ids.size() + " estrategias...");
        for (Long id : ids) {
            detenerEstrategia(id);
        }
        System.out.println("Todas las estrategias han sido pausadas.");
    }

    // ========================================================================
    // SECCIÓN 2: CONSULTAS E INFORMACIÓN (LISTADOS)
    // ========================================================================

    /**
     * Lista las estrategias que tienen un hilo de ejecución activo en TradingService.
     */
    public List<String> listarEstrategiasEnEjecucion() {
        Set<Long> idsActivos = tradingService.getIdsEstrategiasActivas();
        if (idsActivos.isEmpty()) {
            return List.of("No hay estrategias en ejecución.");
        }

        List<String> reporte = new ArrayList<>();
        for (long id : idsActivos) {
            instanciaRepo.findById(id).ifPresent(inst -> {
                String linea = String.format("ID: %d | Estrategia: %s | Symbol: %s | Capital Actual: %s | Estado: %s",
                        inst.getId(),
                        inst.getNombreEstrategia(),
                        inst.getSimbolos(),
                        inst.getCapitalReservado(), // Capital remanente en el silo
                        inst.getEstado());
                reporte.add(linea);
            });
        }
        return reporte;
    }

    /**
     * Lista las estrategias que están en base de datos con estado "DETENIDA".
     * Muestra el capital que tienen retenido.
     */
    public List<String> listarEstrategiasDetenidas() {
        List<InstanciaEstrategia> lista = instanciaRepo.findByEstado("DETENIDA");

        if (lista.isEmpty()) {
            return List.of("No hay estrategias en pausa (DETENIDA).");
        }

        return lista.stream().map(inst ->
                String.format("ID: %d | %s | Capital Retenido: %s | (Usa 'term %d' para liberar fondos)",
                        inst.getId(),
                        inst.getNombreEstrategia(),
                        inst.getCapitalReservado(),
                        inst.getId())
        ).collect(Collectors.toList());
    }

    /**
     * Lista los archivos .py físicos disponibles en la carpeta de estrategias.
     */
    public List<String> listarEstrategias() {
        File folder = new File(PathConfig.STRATEGIES_DIR);
        File[] files = folder.listFiles((dir, name) -> name.endsWith(".py"));

        if (files == null || files.length == 0) {
            return List.of("No se encontraron archivos en: " + PathConfig.STRATEGIES_DIR);
        }

        return Arrays.stream(files)
                .map(f -> "- " + f.getName().replace(".py", ""))
                .collect(Collectors.toList());
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
                System.err.println("Advertencia: No hay velas para " + symbol + " en " + tf);
            }
        }

        if (velasPorSimbolo.isEmpty()) {
            ConsoleLoader.getInstance().stop();
            System.err.println("Abortando: Sin datos para procesar.");
            return;
        }

        // 2. Ejecutar motor
        String strategyPath = PathConfig.getValidStrategyPath(nombreEstra);
        String jsonResultado = backtestingService.ejecutarBacktest(strategyPath, tf, velasPorSimbolo);
        ConsoleLoader.getInstance().stop();

        // 3. Guardar resultados
        if (jsonResultado != null && !jsonResultado.isEmpty()) {
            fileService.guardarResultadosCompletos(nombreEstra, tf, jsonResultado);
            System.out.println("Resultados guardados en: " + PathConfig.RESULTS_DIR + File.separator + nombreEstra);
        } else {
            System.err.println("El motor de backtest no devolvió resultados.");
        }
    }
}